---
type: research
status: completed
tags: [research, archive, trading, agent]
---

# Research: Normalization Approaches for Trading Data

**Status:** Complete
**Researcher:** Claude Code
**Date:** 2025-12-19

---

## Research Question

How should we normalize trading data to make it relative (regime-independent)?

The goal is to ensure the LLM agent never sees absolute values that could leak future information or be regime-dependent. All metrics should be relative to their historical context.

---

## Executive Summary

**Recommendation: Percentile Rank as the primary normalization method, with configurable lookback windows per metric.**

Key findings:
1. **Percentile rank is the industry standard** for options trading (IV Rank, IV Percentile)
2. **LLMs struggle with absolute numerical values** - they focus on local details and miss relational patterns
3. **0-1 bounded scales are most intuitive** for LLM reasoning
4. **252-day lookback is standard** for annual context, but shorter windows (30, 60 days) useful for different signals
5. **Z-score is valuable as secondary metric** for mean-reversion strategies, but unbounded values are harder for LLMs to reason about

---

## Professional System Analysis

### QuantConnect / Lean

QuantConnect focuses on **price data normalization** (splits, dividends) rather than indicator normalization. Key patterns:

- `DataNormalizationMode.Adjusted` - Default, adjusts for splits and dividends
- `DataNormalizationMode.Raw` - For options trading (required for accuracy)
- No built-in percentile/z-score functions - left to algorithm implementation
- Uses entire historical period for adjustments (not rolling windows)

**Takeaway:** QuantConnect defers indicator normalization to the user, focusing on data integrity.

Sources: [QuantConnect Data Normalization](https://www.quantconnect.com/forum/discussion/2604/using-datanormalizationmode-raw-and-history/), [QuantConnect Misconceptions](https://www.quantconnect.com/docs/v2/cloud-platform/datasets/misconceptions)

### Zipline / Quantopian

Zipline provides **built-in factor normalization methods** in its Pipeline API:

```python
# Z-score normalization
factor.zscore(mask=None, groupby=None)
# => (value - mean) / stddev for each row

# Percentile-based filtering
factor.percentile_between(min_percentile, max_percentile)

# Rank normalization
factor.rank(method='ordinal', ascending=True)

# Mean-centering
factor.demean(mask=None, groupby=None)

```

**Best practice pattern** for combining factors:

```python
# Winsorize extremes + z-score before combining
factor1 = SomeFactor().zscore().winsorize(min_percentile=0.03, max_percentile=0.97)
factor2 = AnotherFactor().zscore().winsorize(min_percentile=0.03, max_percentile=0.97)
combined = factor1 + factor2

```

**Key insight:** Z-score is sensitive to outliers. The recommended pattern is to exclude extremes (1st/99th percentile) when computing z-scores:

```python
base = MyFactor()
normalized = base.zscore(mask=base.percentile_between(1, 99))

```

Sources: [Zipline API Reference](https://zipline.ml4trading.io/appendix.html), [Zipline Factor Source](https://github.com/quantopian/zipline/blob/master/zipline/pipeline/factors/factor.py)

### Backtrader

Backtrader uses **standard technical indicator patterns**:

- Bollinger Bands: +-2 standard deviations from SMA
- Z-score for pair trading: `Z = (value - SMA(n)) / StdDev(n)`
- Modified Z-score: Uses median instead of mean (more robust to outliers)

**Common configuration:**
- Short-term: 20-day lookback
- Medium-term: 50-day lookback
- Long-term: 200-day lookback

Sources: [Backtrader Indicators Reference](https://www.backtrader.com/docu/indautoref/), [Backtrader Community on Z-Score](https://community.backtrader.com/topic/5/linear-regression-and-std-211)

---

## Options-Specific: IV Rank vs IV Percentile

This is the most relevant prior art for our use case.

### IV Rank

```
IV Rank = (Current IV - 52-week Low) / (52-week High - 52-week Low)

```

**Pros:**
- Simple to understand: "Where is IV between its high and low?"
- Single extreme value anchors the scale
- Industry-standard terminology

**Cons:**
- **Vulnerable to outliers**: One spike distorts the scale for months
- After extreme events, readings stay compressed
- Doesn't reflect typical conditions

### IV Percentile

```
IV Percentile = (Days with IV below current) / 252

```

**Pros:**
- **Robust to outliers**: Single spike has minimal impact
- Reflects actual distribution of historical values
- Better mean-reversion indicator
- Adapts as volatility regime changes

**Cons:**
- Slightly less intuitive (distribution vs range)
- Requires full historical dataset (not just min/max)

### Industry Recommendation

**IV Percentile is preferred** by sophisticated traders because:
1. It answers "How often is IV this high/low?" not "How close to extremes?"
2. Adapts when market personality changes
3. More reliable for mean-reversion strategies

**Standard lookback: 252 trading days (1 year)**

Additional common periods:
- 30 days (short-term)
- 60 days (medium-term)
- 126 days (6 months)

Sources: [projectfinance IV Rank vs Percentile](https://www.projectfinance.com/iv-rank-percentile/), [Barchart IV Guide](https://www.barchart.com/education/iv_rank_vs_iv_percentile), [Options Trading IQ](https://optionstradingiq.com/iv-rank-vs-iv-percentile/)

---

## Machine Learning / Academic Perspective

### Rolling Window Normalization

Research on quantitative trading with ML shows:

- **50-period rolling window** normalization often outperforms full-dataset normalization
- Exponentially-spaced window experiments recommended: [5, 10, 20, 40, 80, 160] rather than linear
- Rolling window helps model adapt to changing regimes

**Common approaches:**
1. Rolling Z-score: `(value - rolling_mean) / rolling_stddev`
2. Rolling percentile rank: Position in last N observations
3. Rolling min-max: `(value - rolling_min) / (rolling_max - rolling_min)`

Sources: [Alpha Scientist Feature Engineering](https://alphascientist.com/feature_engineering.html), [Machine Learning Mastery Time Series](https://machinelearningmastery.com/normalize-standardize-time-series-data-python/)

### When to Use Each Method

| Method | Best For | Avoid When |
|--------|----------|------------|
| Z-score | Gaussian data, mean-reversion | Heavy outliers, fat tails |
| Percentile | Non-Gaussian, robust to outliers | Very small datasets |
| Min-Max | Neural networks, bounded input needed | Extreme outliers present |
| Log returns | Price changes, multiplicative processes | Values can be negative |

Source: [Normalization vs Standardization Analysis](https://towardsdatascience.com/normalization-vs-standardization-quantitative-analysis-a91e8a79cebf)

---

## Critical Finding: LLM Numerical Reasoning

### The Agent Trading Arena Study (2025)

This research is **highly relevant** to our use case. Key findings:

**LLMs struggle with plain-text numerical data:**
- Focus on absolute values instead of trends
- Overlook percentage changes and relational patterns
- Overemphasize recent values even when context says otherwise
- Fail to capture global patterns, fixate on local details

**Visual representations dramatically improve performance:**
- GPT-4o improved total returns from 33.65% to 47.69% with visual input
- Charts help LLMs understand trends and relationships
- Combined text + visual achieves best results

**Implications for our design:**
1. **Don't present raw numbers** - Always normalize to intuitive scales
2. **Percentile rank (0-1) is ideal** - Bounded, intuitive, comparable across metrics
3. **Consider ASCII/text visualizations** - Even simple bars can help
4. **Relative comparisons > absolute values** - "Higher than 73% of history" vs "IV = 0.25"

Sources: [Agent Trading Arena Paper](https://arxiv.org/html/2502.17967v2), [LLM Trading Agent Survey](https://arxiv.org/html/2408.06361v1)

---

## Recommendation

### Primary Approach: Percentile Rank

Use **percentile rank** as the default normalization for all metrics:

```clojure
(defn percentile-rank
  "Calculate where current value sits in historical distribution.
   Returns 0.0-1.0 (0 = historical min, 1 = historical max)."
  [current-value historical-values]
  (let [n (count historical-values)
        below (count (filter #(< % current-value) historical-values))]
    (/ below n)))

```

**Rationale:**
1. **Industry standard** for options trading (IV Percentile preferred over IV Rank)
2. **Robust to outliers** - single spike doesn't distort scale
3. **Bounded 0-1 scale** - intuitive for LLM reasoning
4. **Non-parametric** - no Gaussian assumption
5. **Comparable across metrics** - 0.8 means same thing for IV, skew, volume

### Secondary Approach: Z-Score (Optional)

Provide z-score as secondary metric for mean-reversion strategies:

```clojure
(defn z-score
  "How many standard deviations from rolling mean.
   Useful for mean-reversion signals."
  [current-value historical-values]
  (let [mean (stats/mean historical-values)
        stddev (stats/stddev historical-values)]
    (if (zero? stddev)
      0.0
      (/ (- current-value mean) stddev))))

```

**When to use:**
- Mean-reversion strategies want magnitude (how extreme?)
- Pair trading spread analysis
- When Gaussian distribution is reasonable

### Per-Metric Recommendations

| Metric | Primary | Secondary | Default Lookback | Rationale |
|--------|---------|-----------|------------------|-----------|
| IV | Percentile | Z-score | 252 days | Industry standard for IV Percentile |
| Skew | Percentile | - | 60 days | Skew regimes change faster |
| Volume | Percentile | Z-score | 30 days | Volume patterns are short-term |
| Price | Percentile | MA ratio | 252 days | Annual context for price levels |
| Term Structure | Percentile | - | 60 days | Changes with macro conditions |
| Gamma Rent | Percentile | - | 30 days | Very regime-dependent |

### Configurable Lookbacks

**Yes, lookback should be configurable per-metric.** Different metrics have different characteristic timescales.

```clojure
;; Default lookbacks (can override)
(def default-lookbacks
  {:iv 252        ; 1 year - standard for IV percentile
   :skew 60       ; ~3 months - skew regimes shift
   :volume 30     ; 1 month - volume patterns are transient
   :price 252     ; 1 year - annual price context
   :term-slope 60 ; ~3 months - term structure shifts
   :gamma-rent 30 ; 1 month - very short-term signal
   })

;; Agent can override
(swap! ctx assoc ::lookback {:iv 126 :skew 30})

```

### Output Format for LLM

Present normalized metrics with context:

```clojure
;; Good - intuitive for LLM
{:iv-percentile 0.73
 :iv-percentile-label :elevated  ; > 0.65
 :iv-percentile-context "IV higher than 73% of past year"}

;; Bad - raw numbers without context
{:iv 0.285}

```

Consider ASCII visualization for complex data:

```clojure
;; Visual context helps LLMs understand
(defn ascii-distribution [percentile]
  (let [bars (int (* percentile 10))]
    (str "[" (apply str (repeat bars "#"))
         (apply str (repeat (- 10 bars) "-")) "]")))

;; => "[#######---]" for 0.73

```

---

## Code Examples

### Core Percentile Rank Implementation

```clojure
(ns seon.algorithmic-trading.normalization)

(defn percentile-rank
  "Calculate percentile rank of current value vs historical values.

   Returns 0.0-1.0:
   - 0.0 = lower than all historical values
   - 0.5 = median
   - 1.0 = higher than all historical values

   Uses exclusive method (count strictly below / total count)."
  [current historical]
  (if (empty? historical)
    nil
    (let [n (count historical)
          below (count (filter #(< % current) historical))]
      (double (/ below n)))))

(defn percentile-rank-with-window
  "Calculate percentile rank using rolling window from database."
  [db ticker metric lookback-days as-of]
  (let [historical (query-historical-values db ticker metric lookback-days as-of)
        current (get-current-value db ticker metric as-of)]
    (when (and current (seq historical))
      (percentile-rank current historical))))

```

### Z-Score Implementation

```clojure
(defn z-score
  "Calculate z-score: (value - mean) / stddev.

   Returns nil if insufficient data or zero stddev.
   Optionally winsorize to remove outliers before computing stats."
  ([current historical]
   (z-score current historical nil))
  ([current historical {:keys [winsorize-pct]}]
   (let [values (if winsorize-pct
                  (winsorize historical winsorize-pct)
                  historical)]
     (when (>= (count values) 2)
       (let [mean (stats/mean values)
             stddev (stats/stddev values)]
         (when (pos? stddev)
           (/ (- current mean) stddev)))))))

(defn winsorize
  "Replace extreme values with percentile boundaries.
   E.g., winsorize-pct 0.05 replaces values below 5th and above 95th percentile."
  [values pct]
  (let [sorted (sort values)
        n (count sorted)
        low-idx (int (* n pct))
        high-idx (int (* n (- 1 pct)))
        low-val (nth sorted low-idx)
        high-val (nth sorted high-idx)]
    (mapv (fn [v]
            (cond
              (< v low-val) low-val
              (> v high-val) high-val
              :else v))
          values)))

```

### Labeled Output for LLM

```clojure
(def percentile-labels
  "Labels for percentile ranges - intuitive for LLM reasoning."
  [[0.00 0.10 :very-low]
   [0.10 0.25 :low]
   [0.25 0.40 :below-average]
   [0.40 0.60 :average]
   [0.60 0.75 :above-average]
   [0.75 0.90 :high]
   [0.90 1.00 :very-high]])

(defn percentile->label [pct]
  (some (fn [[low high label]]
          (when (and (>= pct low) (< pct high))
            label))
        percentile-labels))

(defn format-for-agent
  "Format normalized metric for LLM consumption.

   Returns map with:
   - :value - the normalized value (0-1)
   - :label - human-readable label
   - :context - explanatory string
   - :visual - ASCII visualization"
  [metric-name percentile lookback-days]
  {:value percentile
   :label (percentile->label percentile)
   :context (format "%s is higher than %.0f%% of the past %d days"
                    (name metric-name)
                    (* percentile 100)
                    lookback-days)
   :visual (ascii-distribution percentile)})

;; Example output:
;; {:value 0.73
;;  :label :above-average
;;  :context "iv is higher than 73% of the past 252 days"
;;  :visual "[#######---]"}

```

---

## References

### Professional Trading Systems

- [QuantConnect Data Normalization](https://www.quantconnect.com/forum/discussion/2604/using-datanormalizationmode-raw-and-history/)
- [Zipline API Reference - Factor Methods](https://zipline.ml4trading.io/appendix.html)
- [Zipline Pipeline Factors Source](https://github.com/quantopian/zipline/blob/master/zipline/pipeline/factors/factor.py)
- [Backtrader Indicators Reference](https://www.backtrader.com/docu/indautoref/)

### Options Trading Standards

- [IV Rank vs IV Percentile - projectfinance](https://www.projectfinance.com/iv-rank-percentile/)
- [IV Rank vs IV Percentile - Barchart](https://www.barchart.com/education/iv_rank_vs_iv_percentile)
- [Options Trading IQ - IV Analysis](https://optionstradingiq.com/iv-rank-vs-iv-percentile/)

### Machine Learning / Academic

- [Alpha Scientist - Feature Engineering](https://alphascientist.com/feature_engineering.html)
- [Machine Learning Mastery - Time Series Normalization](https://machinelearningmastery.com/normalize-standardize-time-series-data-python/)
- [Normalization vs Standardization Analysis](https://towardsdatascience.com/normalization-vs-standardization-quantitative-analysis-a91e8a79cebf)
- [RL Framework for Quantitative Trading](https://arxiv.org/html/2411.07585v1)

### LLM + Trading Research

- [Agent Trading Arena - LLM Numerical Understanding](https://arxiv.org/html/2502.17967v2)
- [LLM Trading Agent Survey](https://arxiv.org/html/2408.06361v1)

---

## Summary of Key Decisions

1. **Primary normalization: Percentile rank** (0-1 scale, robust to outliers, industry standard)
2. **Secondary normalization: Z-score** (for mean-reversion, with winsorization)
3. **Default lookbacks vary by metric** (IV: 252, skew: 60, volume: 30)
4. **Lookbacks are configurable** per-metric in ctx
5. **Output includes labels and context** for LLM reasoning
6. **Consider ASCII visualization** to help LLMs understand distributions
