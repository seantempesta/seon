(ns seon.dsl.primitives
  "Financial DSL primitives for the reasoning agent.

  Provides executable functions that the LLM can compose:
  - iv-rank: IV percentile rank
  - term-structure-slope: IV term structure slope
  - put-call-ratio: Volume/OI based sentiment
  - skew-index: 25-delta put/call IV spread
  - gamma-rent: Gamma/Theta ratio

  Each primitive queries the XTDB database and returns
  a numeric or categorical result.

  TEMPORAL SUPPORT:
  All primitives accept an optional `opts` map with:
  - :as-of - Instant to lock queries to (for backtesting/agent sessions)

  Example:
    (iv-rank node \"SPY\" 126 {:as-of #inst \"2025-07-15T21:00:00Z\"})
    ;; Only sees data with valid-time <= 2025-07-15"
  (:require [seon.db.queries :as q]
            [seon.db.node :as node]
            [xtdb.api :as xt]))

;;; ---------------------------------------------------------------------------
;;; Helper Functions
;;; ---------------------------------------------------------------------------

(defn- calculate-percentile
  "Calculate the nth percentile of a sequence of values.

  Args:
    values - Sequence of numeric values
    p - Percentile (0-100)

  Returns:
    Value at the nth percentile, or nil if values is empty"
  [values p]
  (when (seq values)
    (let [sorted (sort values)
          n (count sorted)
          idx (int (* (/ p 100.0) (dec n)))]
      (nth sorted idx))))

(defn- calculate-percentile-rank
  "Calculate what percentile a value is in a sequence.

  Args:
    values - Sequence of numeric values
    current-value - Value to find percentile for

  Returns:
    Percentile rank [0.0, 1.0], or nil if values is empty"
  [values current-value]
  (when (and (seq values) current-value)
    (let [below-count (count (filter #(<= % current-value) values))]
      (/ (double below-count) (count values)))))

;;; ---------------------------------------------------------------------------
;;; Volatility Primitives
;;; ---------------------------------------------------------------------------

(defn iv-rank
  "Calculate the percentile rank of current IV vs historical.

  Queries ATM (delta 0.4-0.6) options to get representative IV,
  then calculates what percentile the current IV is vs all historical values.

  Used for: Volatility arbitrage signals

  Args:
    db - XTDB node
    ticker - Underlying symbol (keyword or string)
    lookback - Lookback period in days (CURRENTLY IGNORED - queries all history)
               TODO: Implement temporal filtering using XTDB v2 system-time ranges
    opts - Optional map with :as-of for temporal locking

  Returns:
    Percentile rank [0.0, 1.0], or 0.5 if no data"
  ([db ticker]
   (iv-rank db ticker 252 {}))
  ([db ticker lookback]
   (iv-rank db ticker lookback {}))
  ([db ticker lookback opts]
   (let [ticker-str (name ticker)
         query-opts {:current-time (:as-of opts)}
         ;; Query all historical ATM IV values (up to as-of date if specified)
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
       ;; Get the most recent IV (latest valid-from)
       (let [sorted-results (sort-by :xt/valid-from results)
             current-iv (:quote/iv (last sorted-results))]
         (or (calculate-percentile-rank historical-ivs current-iv) 0.5))
       ;; No data - return neutral value
       0.5))))

(defn iv-percentile
  "Get the nth percentile of historical IV.

  Queries ATM (delta 0.4-0.6) options to get representative IV,
  then calculates the IV value at the specified percentile.

  Args:
    db - XTDB node
    ticker - Underlying symbol (keyword or string)
    percentile - Target percentile (0-100)
    lookback - Lookback period in days (CURRENTLY IGNORED - queries all history)
               TODO: Implement temporal filtering using XTDB v2 system-time ranges
    opts - Optional map with :as-of for temporal locking

  Returns:
    IV value at the given percentile, or 0.20 if no data"
  ([db ticker percentile]
   (iv-percentile db ticker percentile 252 {}))
  ([db ticker percentile lookback]
   (iv-percentile db ticker percentile lookback {}))
  ([db ticker percentile lookback opts]
   (let [ticker-str (name ticker)
         query-opts {:current-time (:as-of opts)}
         ;; Query all historical ATM IV values (up to as-of date if specified)
         results (node/query db
                             (xt/template
                              (-> (from :option-greeks
                                        [asset/ticker quote/iv greeks/delta])
                                  (where (= asset/ticker ~ticker-str)
                                         (> greeks/delta 0.4)
                                         (< greeks/delta 0.6))))
                             query-opts)
         historical-ivs (map :quote/iv results)]
     (if (seq historical-ivs)
       (or (calculate-percentile historical-ivs percentile) 0.20)
       ;; No data - return neutral IV
       0.20))))

(defn term-structure-slope
  "Calculate the slope of the IV term structure.

  Positive slope = contango (far > near)
  Negative slope = backwardation (near > far)

  Used for: Calendar spread signals

  Args:
    db - Database value
    ticker - Underlying symbol
    opts - Optional map with :as-of for temporal locking

  Returns:
    Slope value (far IV - near IV) / days between them"
  ([db ticker]
   (term-structure-slope db ticker {}))
  ([db ticker opts]
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
       0.0))))

(defn skew-index
  "Calculate the volatility skew (25-delta put - 25-delta call).

  High skew = expensive downside protection
  Low skew = cheap protection (unusual)

  Used for: Skew trading, risk reversals

  Args:
    db - Database value
    ticker - Underlying symbol
    opts - Optional map with :as-of for temporal locking

  Returns:
    Skew value (put IV - call IV)"
  ([db ticker]
   (skew-index db ticker {}))
  ([db ticker opts]
   (let [query-opts {:current-time (:as-of opts)}
         puts (q/options-by-delta db ticker :put -0.30 -0.20 query-opts)
         calls (q/options-by-delta db ticker :call 0.20 0.30 query-opts)]
     (if (and (seq puts) (seq calls))
       (let [avg-put-iv (/ (reduce + (map :quote/iv puts)) (count puts))
             avg-call-iv (/ (reduce + (map :quote/iv calls)) (count calls))]
         (- avg-put-iv avg-call-iv))
       0.0))))

;;; ---------------------------------------------------------------------------
;;; Sentiment Primitives
;;; ---------------------------------------------------------------------------

(defn put-call-ratio
  "Calculate put/call ratio by volume or open interest.

  High ratio = bearish sentiment
  Low ratio = bullish sentiment

  Args:
    db - Database value
    ticker - Underlying symbol
    metric - :volume or :oi (default :volume)
    opts - Optional map with :as-of for temporal locking

  Returns:
    Put/Call ratio"
  ([db ticker]
   (put-call-ratio db ticker :volume {}))
  ([db ticker metric]
   (put-call-ratio db ticker metric {}))
  ([db ticker metric opts]
   (let [query-opts {:current-time (:as-of opts)}
         chain (q/options-chain db ticker query-opts)
         metric-key (if (= metric :oi) :market/oi :market/volume)
         puts (filter #(= :put (:option/type %)) chain)
         calls (filter #(= :call (:option/type %)) chain)
         put-sum (reduce + (map #(get % metric-key 0) puts))
         call-sum (reduce + (map #(get % metric-key 0) calls))]
     (if (pos? call-sum)
       (/ put-sum call-sum)
       0.0))))

;;; ---------------------------------------------------------------------------
;;; Greeks Primitives
;;; ---------------------------------------------------------------------------

(defn gamma-rent
  "Calculate the 'cost' of gamma (Gamma / |Theta|).

  High ratio = cheap gamma (good for scalping)
  Low ratio = expensive gamma

  Used for: Gamma scalping entry signals

  Args:
    db - Database value
    ticker - Underlying symbol
    strike - Strike price (or :atm for at-the-money)
    spot - Current spot price
    opts - Optional map with :as-of for temporal locking

  Returns:
    Gamma/Theta ratio"
  ([db ticker strike spot]
   (gamma-rent db ticker strike spot {}))
  ([db ticker strike spot opts]
   (let [query-opts {:current-time (:as-of opts)}
         target (if (= strike :atm)
                  ;; For ATM, use first result from atm-options (already filtered by delta)
                  (first (q/atm-options db ticker spot 0.05 query-opts))
                  ;; For specific strike, find matching option
                  (first (filter #(= strike (:option/strike %))
                                 (q/options-chain db ticker query-opts))))]
     (if target
       (let [gamma (get target :greeks/gamma 0)
             theta (get target :greeks/theta -0.01)]
         (if (neg? theta)
           (/ gamma (Math/abs theta))
           0.0))
       0.0))))

(defn net-gamma
  "Calculate net gamma exposure at a strike level.

  Used for: Dealer gamma positioning, pinning analysis

  Args:
    db - Database value
    ticker - Underlying symbol
    strike - Strike price
    opts - Optional map with :as-of for temporal locking

  Returns:
    Net gamma (calls - puts)"
  ([db ticker strike]
   (net-gamma db ticker strike {}))
  ([db ticker strike opts]
   (let [query-opts {:current-time (:as-of opts)}
         chain (q/options-chain db ticker query-opts)
         at-strike (filter #(= strike (:option/strike %)) chain)
         calls (filter #(= :call (:option/type %)) at-strike)
         puts (filter #(= :put (:option/type %)) at-strike)]
     (- (reduce + (map #(get % :greeks/gamma 0) calls))
        (reduce + (map #(get % :greeks/gamma 0) puts))))))

(defn vanna
  "Calculate Vanna (dDelta/dVol) for an option.

  Used for: Vol sensitivity analysis

  Args:
    db - Database value
    option-id - OCC symbol
    opts - Optional map with :as-of for temporal locking

  Returns:
    Vanna value"
  ([db option-id]
   (vanna db option-id {}))
  ([db option-id opts]
   ;; Vanna = Delta * (1 - Delta) * sqrt(T) / sigma
   ;; Simplified calculation
   (let [query-opts {:current-time (:as-of opts)}
         opt (q/option-by-occ db option-id query-opts)]
     (if-let [{:keys [greeks/delta quote/iv option/expiry]} opt]
       (let [now (or (:as-of opts) (java.time.Instant/now))
             now-date (java.time.LocalDate/ofInstant now (java.time.ZoneId/of "UTC"))
             days-to-expiry (.between java.time.temporal.ChronoUnit/DAYS now-date expiry)
             sqrt-t (Math/sqrt (/ (max days-to-expiry 0) 365.0))]
         (* delta (- 1 (Math/abs delta)) (/ sqrt-t (max iv 0.01))))
       0.0))))

;;; ---------------------------------------------------------------------------
;;; Correlation Primitives (for Dispersion)
;;; ---------------------------------------------------------------------------

(defn implied-correlation
  "Calculate implied correlation from index and component vols.

  Used for: Dispersion trading signals

  Args:
    db - Database value
    index-ticker - Index symbol (e.g., 'SPX')
    component-tickers - Sequence of component symbols
    weights - Component weights (must sum to 1)

  Returns:
    Implied correlation [0, 1] or nil if not implemented"
  [db index-ticker component-tickers weights]
  ;; TODO: Implement proper correlation calculation
  ;; Requires querying actual volatilities from the database
  ;; σ_I² = Σw_i²σ_i² + ΣΣw_iw_jσ_iσ_jρ_ij
  ;; Solve for average ρ
  ;;
  ;; Current implementation has hardcoded volatilities which makes it unusable.
  ;; Returning nil until properly implemented.
  nil)

;;; ---------------------------------------------------------------------------
;;; Utility Functions
;;; ---------------------------------------------------------------------------

(defn upcoming-events
  "Get upcoming events (earnings, dividends, etc.) for a ticker.

  Used for: Event-driven volatility signals

  Args:
    db - Database value
    ticker - Underlying symbol
    days-ahead - Days to look ahead (default 30)

  Returns:
    Sequence of {:type :date :days-until} maps"
  ([db ticker]
   (upcoming-events db ticker 30))
  ([db ticker days-ahead]
   ;; TODO: Query events table
   []))

(defn open-interest-distribution
  "Get the distribution of open interest by strike.

  Used for: Max pain, pinning analysis

  Args:
    db - Database value
    ticker - Underlying symbol

  Returns:
    Sequence of {:strike :call-oi :put-oi :total-oi} maps"
  [db ticker]
  ;; TODO: Aggregate OI by strike
  [])

;;; ---------------------------------------------------------------------------
;;; DSL Registry
;;; ---------------------------------------------------------------------------

(def primitives
  "Registry of all DSL primitives with their metadata.

  Each entry contains:
  - :fn - The function
  - :args - Argument specification
  - :returns - Return type
  - :description - Human-readable description"
  {:iv-rank {:fn iv-rank
             :args [:db :ticker [:lookback :optional]]
             :returns :percentile
             :description "IV percentile rank vs history"}

   :term-structure-slope {:fn term-structure-slope
                          :args [:db :ticker]
                          :returns :slope
                          :description "IV term structure slope"}

   :skew-index {:fn skew-index
                :args [:db :ticker]
                :returns :spread
                :description "25-delta put/call IV skew"}

   :put-call-ratio {:fn put-call-ratio
                    :args [:db :ticker [:metric :optional]]
                    :returns :ratio
                    :description "Put/call volume or OI ratio"}

   :gamma-rent {:fn gamma-rent
                :args [:db :ticker :strike :spot]
                :returns :ratio
                :description "Gamma/Theta cost ratio"}

   :implied-correlation {:fn implied-correlation
                         :args [:db :index :components :weights]
                         :returns :correlation
                         :description "Implied correlation from dispersed vols"}})
