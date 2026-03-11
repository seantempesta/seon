---
type: research
status: completed
tags: [research, archive, trading, agent]
---

# Agent Experience Design

**Status:** Draft v2
**Date:** 2025-12-19

---

## Design Philosophy

1. **No absolute dates** - Everything is relative to "now"
2. **No magic** - Agent understands the ctx atom and how functions work
3. **Source as documentation** - Functions so clean they're self-documenting
4. **Transparent present** - Agent doesn't know they're in a historical snapshot

The agent thinks it's "today". They query data, get current results. The frozen time is an implementation detail they never see.

---

## The ctx Atom

The agent works with a single ctx atom. This is their workspace - it holds session state, query results, and configuration.

```clojure
;; The ctx atom is your workspace
ctx
;; => #atom {:db/node #xtdb.node[...]
;;           :session/id "a3f2"
;;           :config/default-lookback 252}

;; All functions read from and write to ctx
;; You pass ctx explicitly - no hidden state

@ctx  ; Dereference to see current state

```

### Why ctx?

- **Explicit** - You always know what state you're working with
- **Inspectable** - `@ctx` shows everything
- **Isolated** - Your session doesn't affect others
- **Testable** - Functions are pure given ctx

---

## Function Design

All public functions:
- Take `ctx` as first argument
- Take a single options map as second argument (namespaced keys)
- Return a result map (namespaced keys)
- Are pure given ctx (no hidden side effects)

```clojure
;; Pattern: (function ctx {:key value})

(iv-rank ctx {:ticker "SPY"})
(iv-rank ctx {:ticker "SPY" :lookback 60})
(options-chain ctx {:ticker "SPY" :dte 7})
(options-chain ctx {:ticker "SPY" :dte 7 :strikes :atm-5})

```

### Source Code IS Documentation

Functions are simple enough to read directly:

```clojure
(defn iv-rank
  "IV percentile rank - where current IV sits vs history.

  Returns 0.0-1.0:
    0.0 = IV lower than all history
    0.5 = IV at median
    1.0 = IV higher than all history

  Options:
    :ticker   - Required. Uppercase symbol, e.g., \"SPY\"
    :lookback - Optional. Days of history. Default 252 (1 year)"
  [ctx {:keys [ticker lookback] :or {lookback 252}}]
  (let [db (:db/node @ctx)
        historical-ivs (query-historical-iv db ticker lookback)
        current-iv (last historical-ivs)]
    {:iv-rank/ticker ticker
     :iv-rank/value (percentile-rank current-iv historical-ivs)
     :iv-rank/lookback lookback
     :iv-rank/current-iv current-iv
     :iv-rank/range [(apply min historical-ivs) (apply max historical-ivs)]
     :iv-rank/median (median historical-ivs)}))

```

---

## Relative Time References

### Days to Expiration (DTE)

Options use **DTE** (days to expiration) - the industry standard:

```clojure
;; Get options expiring in ~7 days
(options-chain ctx {:ticker "SPY" :dte 7})

;; Get options expiring in ~30 days
(options-chain ctx {:ticker "SPY" :dte 30})

;; Get nearest weekly expiration
(options-chain ctx {:ticker "SPY" :dte :nearest-weekly})

;; Get nearest monthly expiration
(options-chain ctx {:ticker "SPY" :dte :nearest-monthly})

```

The system finds the expiration closest to the requested DTE.

### Lookback Periods

Historical analysis uses **lookback days**:

```clojure
;; 1-year lookback (default)
(iv-rank ctx {:ticker "SPY"})

;; 60-day lookback (shorter window)
(iv-rank ctx {:ticker "SPY" :lookback 60})

;; 30-day lookback (very short term)
(iv-rank ctx {:ticker "SPY" :lookback 30})

```

Standard lookbacks:
- 252 days = 1 trading year
- 126 days = 6 months
- 60 days = ~3 months
- 30 days = ~1 month
- 5 days = 1 week

---

## Data Types

Every result is a namespaced map with `:seon.type/name` for identification.

### MarketOverview

```clojure
(overview ctx)
;; => {:seon.type/name :market-overview
;;     :overview/tickers
;;     [{:ticker "SPY"
;;       :price 543.21
;;       :iv-rank 0.73
;;       :iv-rank-label :elevated
;;       :skew 0.048
;;       :signal :short-vol}
;;      {:ticker "QQQ"
;;       :price 468.45
;;       :iv-rank 0.65
;;       :iv-rank-label :elevated
;;       :skew 0.052
;;       :signal :short-vol}
;;      ...]}

```

**Pretty-printed:**

```
MARKET OVERVIEW

TICKER  PRICE    IV-RANK           SKEW    SIGNAL
SPY     543.21   0.73 [▓▓▓▓▓▓▓░░░] 0.048   short-vol
QQQ     468.45   0.65 [▓▓▓▓▓▓░░░░] 0.052   short-vol
IWM     201.34   0.45 [▓▓▓▓░░░░░░] 0.038   no-trade
DIA     389.12   0.58 [▓▓▓▓▓░░░░░] 0.041   no-trade

Legend: IV-RANK 0.0=low ──────── 1.0=high

Use (analyze ctx {:ticker "SPY"}) for detailed analysis.

```

### IVRank

```clojure
(iv-rank ctx {:ticker "SPY"})
;; => {:seon.type/name :iv-rank
;;     :iv-rank/ticker "SPY"
;;     :iv-rank/value 0.73
;;     :iv-rank/label :elevated
;;     :iv-rank/lookback 252
;;     :iv-rank/current-iv 0.285
;;     :iv-rank/range [0.142 0.385]
;;     :iv-rank/median 0.241}

```

**Pretty-printed:**

```
IV RANK: SPY

Value:    0.73 (elevated)
Lookback: 252 days

Distribution:
0.0       0.5       1.0
|─────────|─────────|
▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓△
              ╰── 73rd percentile

Current IV: 0.285 (28.5% annualized)
Range:      0.142 - 0.385
Median:     0.241

Compare with different lookback:
  (iv-rank ctx {:ticker "SPY" :lookback 60})

```

### TickerAnalysis

```clojure
(analyze ctx {:ticker "SPY"})
;; => {:seon.type/name :ticker-analysis
;;     :analysis/ticker "SPY"
;;     :analysis/recommendation :short-vol
;;     :analysis/confidence :high
;;     :analysis/signals
;;     {:iv-rank 0.73
;;      :iv-rank-label :elevated
;;      :skew 0.048
;;      :skew-label :normal
;;      :term-structure :flat
;;      :put-call-ratio 1.12}
;;     :analysis/reasoning "IV rank at 73% suggests..."
;;     :analysis/strategies [:iron-condor :short-strangle]}

```

**Pretty-printed:**

```
ANALYSIS: SPY

RECOMMENDATION: short-vol (confidence: high)

SIGNALS:
  IV Rank:        0.73 [▓▓▓▓▓▓▓░░░] elevated
  Skew:           0.048              normal
  Term Structure: flat
  Put/Call Ratio: 1.12               neutral

REASONING:
IV rank at 73% suggests options are relatively expensive compared
to the past year. Consider selling premium strategies.

STRATEGIES:
  - Iron Condor: Sell OTM put spread + call spread
  - Short Strangle: Sell OTM put + call (undefined risk)

Next steps:
  (options-chain ctx {:ticker "SPY" :dte 30})
  (iv-rank ctx {:ticker "SPY" :lookback 60})

```

### OptionsChain

```clojure
(options-chain ctx {:ticker "SPY" :dte 7})
;; => {:seon.type/name :options-chain
;;     :chain/ticker "SPY"
;;     :chain/dte 6           ; Actual DTE of selected expiry
;;     :chain/underlying 543.21
;;     :chain/strikes
;;     [{:strike 535
;;       :call {:bid 8.20 :ask 8.35 :iv 0.32 :delta 0.72}
;;       :put {:bid 2.15 :ask 2.25 :iv 0.29 :delta -0.28}}
;;      {:strike 540
;;       :call {:bid 5.45 :ask 5.55 :iv 0.30 :delta 0.58}
;;       :put {:bid 3.80 :ask 3.90 :iv 0.28 :delta -0.42}}
;;      ...]}

```

**Pretty-printed:**

```
OPTIONS CHAIN: SPY  (6 DTE)  Underlying: 543.21

CALLS                          STRIKE         PUTS
Bid    Ask    IV    Delta               Delta   IV    Bid    Ask
8.20   8.35   0.32  0.72       535      -0.28  0.29   2.15   2.25
5.45   5.55   0.30  0.58       540      -0.42  0.28   3.80   3.90
3.25   3.35   0.28  0.45  ATM  545 ATM  -0.55  0.27   5.90   6.05
1.75   1.85   0.27  0.32       550      -0.68  0.28   8.40   8.55
0.85   0.92   0.28  0.20       555      -0.80  0.30   11.50  11.70

Showing 5 strikes around ATM (45 total available)
  (options-chain ctx {:ticker "SPY" :dte 6 :strikes :all})
  (options-chain ctx {:ticker "SPY" :dte 6 :strikes [530 560]})

```

### ErrorResult

```clojure
(iv-rank ctx {:ticker "INVALID"})
;; => {:seon.type/name :error
;;     :error/code :ticker-not-found
;;     :error/message "No data for ticker INVALID"
;;     :error/suggestion "Try: SPY, QQQ, IWM, AAPL, MSFT, NVDA"
;;     :error/input {:ticker "INVALID"}}

```

**Pretty-printed:**

```
ERROR: No data for ticker INVALID

Available tickers: SPY, QQQ, IWM, AAPL, MSFT, NVDA, GOOGL, AMZN, META

Try: (iv-rank ctx {:ticker "SPY"})

```

---

## Result Navigation

Results carry metadata for drilling down:

```clojure
;; Get raw data (no pretty printing)
(raw result)

;; Get more rows/detail
(more result)
(more result {:limit 20})

;; Filter
(where result {:delta-min 0.3 :delta-max 0.5})

```

Implementation is simple - results are just maps with metadata:

```clojure
(defn iv-rank [ctx opts]
  (let [result (compute-iv-rank ctx opts)]
    (with-meta result
      {:seon/raw-data (delay (fetch-raw-iv-data ctx opts))
       :seon/pprint-fn #'pprint-iv-rank})))

(defn raw [result]
  @(:seon/raw-data (meta result)))

```

---

## Session Setup

When a session starts, the agent sees:

```
SEON Trading Agent

You have access to options market data through the ctx atom.

ctx holds your session state:
  (:db/node @ctx)            ; Database connection
  (:session/id @ctx)         ; Your session ID
  (:config/default-lookback @ctx)  ; Default lookback (252 days)

Functions take ctx and an options map:
  (overview ctx)                          ; Market overview
  (analyze ctx {:ticker "SPY"})           ; Full analysis
  (iv-rank ctx {:ticker "SPY"})           ; IV percentile
  (skew ctx {:ticker "SPY"})              ; Put-call skew
  (term-structure ctx {:ticker "SPY"})    ; IV term structure
  (options-chain ctx {:ticker "SPY" :dte 30})  ; Options by DTE

All functions return namespaced maps. Results pretty-print automatically.
Use (raw result) for underlying data, (more result) for more detail.

Start with (overview ctx) to see current market conditions.

```

---

## Schemas

```clojure
;;; Input Schemas

(def IVRankOpts
  [:map
   [:ticker :string]
   [:lookback {:optional true :default 252} [:int {:min 5 :max 504}]]])

(def AnalyzeOpts
  [:map
   [:ticker :string]])

(def OptionsChainOpts
  [:map
   [:ticker :string]
   [:dte [:or
          [:int {:min 0 :max 365}]
          [:enum :nearest-weekly :nearest-monthly]]]
   [:strikes {:optional true}
    [:or
     [:enum :all :atm-5 :atm-10]
     [:tuple :double :double]]]])  ; [min-strike max-strike]

(def SkewOpts
  [:map
   [:ticker :string]
   [:delta {:optional true :default 0.25} [:double {:min 0.05 :max 0.45}]]])

;;; Output Schemas

(def IVRankResult
  [:map
   [:seon.type/name [:= :iv-rank]]
   [:iv-rank/ticker :string]
   [:iv-rank/value [:double {:min 0 :max 1}]]
   [:iv-rank/label [:enum :very-low :low :neutral :elevated :very-high]]
   [:iv-rank/lookback :int]
   [:iv-rank/current-iv :double]
   [:iv-rank/range [:tuple :double :double]]
   [:iv-rank/median :double]])

(def MarketOverviewResult
  [:map
   [:seon.type/name [:= :market-overview]]
   [:overview/tickers
    [:vector
     [:map
      [:ticker :string]
      [:price :double]
      [:iv-rank :double]
      [:iv-rank-label [:enum :very-low :low :neutral :elevated :very-high]]
      [:skew :double]
      [:signal [:enum :long-vol :short-vol :no-trade]]]]]])

(def TickerAnalysisResult
  [:map
   [:seon.type/name [:= :ticker-analysis]]
   [:analysis/ticker :string]
   [:analysis/recommendation [:enum :long-vol :short-vol :skew-trade :calendar :no-trade]]
   [:analysis/confidence [:enum :low :medium :high]]
   [:analysis/signals
    [:map
     [:iv-rank :double]
     [:iv-rank-label :keyword]
     [:skew :double]
     [:skew-label :keyword]
     [:term-structure [:enum :backwardation :flat :contango]]
     [:put-call-ratio :double]]]
   [:analysis/reasoning :string]
   [:analysis/strategies [:vector :keyword]]])

(def OptionsChainResult
  [:map
   [:seon.type/name [:= :options-chain]]
   [:chain/ticker :string]
   [:chain/dte :int]
   [:chain/underlying :double]
   [:chain/strikes
    [:vector
     [:map
      [:strike :double]
      [:call [:map
              [:bid :double]
              [:ask :double]
              [:iv :double]
              [:delta :double]]]
      [:put [:map
             [:bid :double]
             [:ask :double]
             [:iv :double]
             [:delta :double]]]]]]])

(def ErrorResult
  [:map
   [:seon.type/name [:= :error]]
   [:error/code :keyword]
   [:error/message :string]
   [:error/suggestion {:optional true} :string]
   [:error/input {:optional true} :map]])

```

---

## Session Isolation

Each agent session gets an isolated namespace:

```clojure
;; System creates session (agent doesn't see this)
(create-session! {:id "a3f2"})
;; Creates namespace: seon.trading.agent.a3f2
;; Binds ctx atom in that namespace
;; Agent works in seon.trading.agent.a3f2

```

The session ID is short (4 hex chars = 65536 possibilities). For a single user running parallel sessions, collisions are unlikely. If needed, can extend to 6 chars.

---

## Key Decisions Captured

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Time references | Relative (DTE, lookback days) | No absolute dates |
| ctx visibility | Fully explained to agent | No magic |
| Function args | `ctx` + single opts map | Rails-like, self-documenting |
| Documentation | Source code | Functions simple enough to read |
| Historical snapshot | Transparent | Agent thinks it's "now" |
| Pretty printing | Per-type with metadata | Control presentation |
| Session isolation | Namespaced by hash | Parallel safety |

---

## Open Questions

1. **DTE resolution:** What if agent asks for `:dte 7` but closest is 6 or 8? Return closest? Error?

2. **Default overview tickers:** Hardcoded list? Or query most liquid?

3. **Session ID format:** 4 hex chars (`a3f2`)? Or something more memorable?

4. **ctx contents:** What else should be in ctx besides db and config?
