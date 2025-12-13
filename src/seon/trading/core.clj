(ns seon.trading.core
  "Trading domain public API.

  This is the entry point for trading functionality. All functions
  receive db as the first parameter - domains don't manage their own
  database, they receive it from Seon core.

  Domain Capabilities:
  - Signal analysis: IV rank, skew, term structure, gamma rent, etc.
  - Ticker analysis: Multi-signal analysis with recommendations
  - Data ingestion: ThetaData import, bulk loading

  The capabilities function returns a description suitable for LLM agents
  to discover and understand what this domain can do."
  (:require
   [seon.trading.signals :as signals]
   [seon.trading.analysis :as analysis]))

(defn capabilities
  "Returns description of trading domain capabilities for LLM agents.

  This enables autonomous discovery - agents can query what signals,
  analyses, and data operations are available without hardcoding.

  Returns:
    Map with :domain, :description, and capability categories

  Example:
    (capabilities)
    => {:domain :trading
        :description \"Options trading analysis and data management\"
        :signals [:iv-rank :skew-index ...]
        :analysis [:analyze-ticker]
        :data [:thetadata-import :bulk-load]}"
  []
  {:domain :trading
   :description "Options trading analysis and data management"
   :signals [:iv-rank
             :skew-index
             :term-structure-slope
             :gamma-rent
             :iv-percentile
             :put-call-ratio
             :volume-oi-ratio]
   :analysis [:analyze-ticker]
   :data [:thetadata-import :bulk-load]
   :temporal-support true
   :notes "All functions support :as-of temporal queries for backtesting"})

(defn analyze-ticker
  "Analyze a ticker for trading signals and generate recommendations.

  This is the primary entry point for LLM agents performing trading analysis.
  Returns a comprehensive view of all signals, categorizations, and
  actionable recommendations.

  Args:
    db - XTDB node (passed from Seon core)
    ticker - String ticker symbol (e.g., \"SPY\")
    opts - Optional map:
           :as-of - Instant to lock temporal queries
           :dte - Days to expiration (default: 45)

  Returns:
    Map with:
      :signals - All computed signals
      :recommendation - :buy-calls, :buy-puts, :sell-premium, :no-trade
      :reasoning - Human-readable explanation
      :confidence - :high, :medium, :low

  Example:
    (analyze-ticker db \"SPY\" {:as-of #inst \"2025-07-15\"})
    => {:signals {:iv-rank 0.85 :skew-index 0.06 ...}
        :recommendation :sell-premium
        :reasoning \"High IV rank (85th percentile) suggests...\"
        :confidence :high}

  Delegates to:
    seon.trading.analysis/analyze-ticker"
  [db ticker opts]
  (analysis/analyze-ticker db ticker opts))

;; Future API functions as domain grows:
;; (defn compute-signal [db ticker signal-name opts] ...)
;; (defn backtest-strategy [db strategy opts] ...)
;; (defn import-thetadata [db ticker start-date end-date] ...)
