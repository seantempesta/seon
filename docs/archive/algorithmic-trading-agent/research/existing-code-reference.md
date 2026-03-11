# Existing Code Reference

This document summarizes the existing trading code that the new `seon.algorithmic-trading` namespace will build upon.

---

## Key Files

| File | Purpose |
|------|---------|
| `src/seon/trading/signals.clj` | Signal primitives (iv-rank, skew, etc.) |
| `src/seon/trading/analysis.clj` | High-level analysis interface |
| `src/seon/db/node.clj` | XTDB query wrapper |
| `src/seon/db/queries.clj` | Common query patterns |

---

## seon.trading.signals

Signal primitives for options analysis. All primitives accept temporal options.

### Primitives Available

```clojure
;; IV Rank - percentile of current IV vs history
(iv-rank db ticker lookback opts)
;; => 0.73 (current IV higher than 73% of history)

;; IV Percentile - IV value at nth percentile
(iv-percentile db ticker percentile lookback opts)
;; => 0.25 (the IV value at 75th percentile)

;; Term Structure Slope - contango/backwardation
(term-structure-slope db ticker opts)
;; => 0.0005 (positive = contango, far > near)

;; Skew Index - 25-delta put/call IV spread
(skew-index db ticker opts)
;; => 0.04 (4% more for puts than calls)

;; Put/Call Ratio - sentiment indicator
(put-call-ratio db ticker metric opts)
;; => 1.2 (more puts than calls)

;; Gamma Rent - cost of gamma
(gamma-rent db ticker strike spot opts)
;; => 0.05 (gamma/|theta| ratio)

```

### Temporal Support

All primitives accept `opts` map with `:as-of`:

```clojure
(iv-rank node "SPY" 126 {:as-of #inst "2024-06-15T21:00:00Z"})
;; Only sees data with valid-time <= 2024-06-15

```

This is the "frozen present" model - queries are locked to a point in time.

### Registry

Primitives are registered in `signals/primitives` map with metadata:

```clojure
{:iv-rank {:fn iv-rank
           :args [:db :ticker [:lookback :optional]]
           :returns :percentile
           :description "IV percentile rank vs history"}
 ...}

```

---

## seon.trading.analysis

Higher-level analysis that combines signals into recommendations.

### Main Function

```clojure
(analyze-ticker node ticker opts)
;; => {:ticker "SPY"
;;     :signals {:iv-rank 0.35
;;               :iv-rank-label :neutral
;;               :skew 0.068
;;               :skew-label :normal
;;               :term-slope 7.35e-7
;;               :term-label :flat
;;               :gamma-rent 0.023
;;               :gamma-label :moderate
;;               :atm-iv 0.328
;;               :spot 602.57}
;;     :recommendation :no-trade
;;     :confidence :high
;;     :reasoning "IV Rank at 35% is neutral..."
;;     :strategies []
;;     :strategy-details []}

```

### Signal Labels

Signals are labeled into categories:

| Signal | Labels |
|--------|--------|
| iv-rank | :low, :neutral, :high |
| skew | :low, :normal, :elevated |
| term-structure | :backwardation, :flat, :contango |
| gamma-rent | :cheap, :moderate, :expensive |

### Thresholds

Hardcoded thresholds for labeling:

```clojure
{:iv-rank {:low 0.20 :high 0.80}
 :skew {:low 0.02 :high 0.08}
 :term-slope {:backwardation -0.0005 :contango 0.0005}
 :gamma-rent {:cheap 0.06 :expensive 0.02}}

```

### Recommendation Logic

Priority order:
1. Extreme IV rank (>0.85 or <0.15) - strongest signal
2. High IV + normal/elevated skew - short vol
3. Low IV + low/normal skew - long vol
4. Neutral IV + elevated skew - skew trade
5. Cheap gamma - gamma scalping opportunity
6. Otherwise - no trade

---

## Notes for New Design

### What to Keep

1. **Temporal support** - `:as-of` opts pattern works well
2. **Signal primitives** - Wrap these, don't rewrite
3. **Registry pattern** - `signals/primitives` for discovery

### What to Change

1. **Single namespace** - Agent shouldn't need multiple requires
2. **ctx atom pattern** - Replace positional args with ctx reads
3. **Recording** - Add REPL capture for training data
4. **Normalization** - Research best approach (currently only percentile rank)

### Questions Raised

1. `iv-rank` ignores `lookback` parameter (queries all history) - is this a bug?
2. Thresholds are hardcoded - should they be configurable?
3. Labels are coarse - should we provide finer granularity?
4. No z-score or other normalization options currently

---

## XTDB Query Pattern

The existing code uses this pattern:

```clojure
(require '[seon.db.node :as node])
(require '[xtdb.api :as xt])

;; Query with temporal lock
(node/query db
  (xt/template
    (-> (from :option-greeks [asset/ticker quote/iv greeks/delta])
        (where (= asset/ticker ~ticker-str)
               (> greeks/delta 0.4)
               (< greeks/delta 0.6))))
  {:current-time as-of})

```

Key points:
- Use `xt/template` for dynamic values
- Pass `{:current-time instant}` for temporal locking
- Results are maps with namespaced keys
