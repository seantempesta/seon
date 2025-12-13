(ns seon.generators
  "Custom Malli generators for property-based testing.

  Provides domain-specific generators that produce valid
  financial data for testing strategies and calculations."
  (:require [malli.generator :as mg]
            [clojure.test.check.generators :as gen]
            [seon.db.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Price Generators
;;; ---------------------------------------------------------------------------

(def gen-spot-price
  "Generator for realistic spot prices ($10 - $1000)."
  (gen/double* {:min 10.0 :max 1000.0 :NaN? false :infinite? false}))

(def gen-strike-price
  "Generator for strike prices."
  (gen/double* {:min 5.0 :max 2000.0 :NaN? false :infinite? false}))

(def gen-option-price
  "Generator for option prices (bid/ask)."
  (gen/double* {:min 0.01 :max 500.0 :NaN? false :infinite? false}))

;;; ---------------------------------------------------------------------------
;;; Volatility Generators
;;; ---------------------------------------------------------------------------

(def gen-iv
  "Generator for implied volatility (5% - 200%)."
  (gen/double* {:min 0.05 :max 2.0 :NaN? false :infinite? false}))

(def gen-realized-vol
  "Generator for realized volatility."
  (gen/double* {:min 0.01 :max 3.0 :NaN? false :infinite? false}))

(def gen-vol-spread
  "Generator for IV - RV spread."
  (gen/double* {:min -0.5 :max 0.5 :NaN? false :infinite? false}))

;;; ---------------------------------------------------------------------------
;;; Greeks Generators
;;; ---------------------------------------------------------------------------

(def gen-delta
  "Generator for delta values (-1 to 1)."
  (gen/double* {:min -1.0 :max 1.0 :NaN? false :infinite? false}))

(def gen-gamma
  "Generator for gamma (non-negative)."
  (gen/double* {:min 0.0 :max 0.5 :NaN? false :infinite? false}))

(def gen-vega
  "Generator for vega (non-negative)."
  (gen/double* {:min 0.0 :max 50.0 :NaN? false :infinite? false}))

(def gen-theta
  "Generator for theta (typically negative)."
  (gen/double* {:min -5.0 :max 0.0 :NaN? false :infinite? false}))

(def gen-greeks
  "Generator for a complete Greeks map."
  (gen/let [delta gen-delta
            gamma gen-gamma
            vega gen-vega
            theta gen-theta]
    {:delta delta
     :gamma gamma
     :vega vega
     :theta theta}))

;;; ---------------------------------------------------------------------------
;;; Time Generators
;;; ---------------------------------------------------------------------------

(def gen-days-to-expiry
  "Generator for days to expiration (1 - 365)."
  (gen/choose 1 365))

(def gen-expiry-instant
  "Generator for expiration timestamps."
  (gen/let [days gen-days-to-expiry]
    (.plus (java.time.Instant/now)
           (java.time.Duration/ofDays days))))

(def gen-historical-instant
  "Generator for historical timestamps (up to 2 years ago)."
  (gen/let [days (gen/choose 1 730)]
    (.minus (java.time.Instant/now)
            (java.time.Duration/ofDays days))))

;;; ---------------------------------------------------------------------------
;;; Composite Generators
;;; ---------------------------------------------------------------------------

(def gen-ticker
  "Generator for stock ticker symbols."
  (gen/elements ["AAPL" "MSFT" "GOOGL" "AMZN" "META"
                 "NVDA" "TSLA" "SPY" "QQQ" "IWM"
                 "XLF" "XLE" "XLK" "XLV" "XLU"]))

(def gen-option-type
  "Generator for option type."
  (gen/elements [:call :put]))

(def gen-occ-symbol
  "Generator for OCC option symbols.

  Format: AAPL230616C00150000
          ticker + YYMMDD + C/P + strike*1000"
  (gen/let [ticker gen-ticker
            year (gen/choose 24 26)
            month (gen/choose 1 12)
            day (gen/choose 1 28)
            opt-type gen-option-type
            strike (gen/choose 50 500)]
    (format "%s%02d%02d%02d%s%08d"
            ticker year month day
            (if (= opt-type :call) "C" "P")
            (* strike 1000))))

(def gen-valid-option-quote
  "Generator for a complete, valid option quote."
  (gen/let [ticker gen-ticker
            occ gen-occ-symbol
            strike gen-strike-price
            opt-type gen-option-type
            expiry gen-expiry-instant
            quote-instant gen-historical-instant
            bid gen-option-price
            spread (gen/double* {:min 0.01 :max 0.5 :NaN? false :infinite? false})
            iv gen-iv
            greeks gen-greeks
            volume (gen/choose 0 100000)]
    {:xt/id (str occ "-" (.toString quote-instant))
     :asset/ticker ticker
     :option/id occ
     :option/strike strike
     :option/type opt-type
     :option/expiry expiry
     :quote/bid bid
     :quote/ask (+ bid (* bid spread))
     :quote/iv iv
     :greeks/delta (:delta greeks)
     :greeks/gamma (:gamma greeks)
     :greeks/vega (:vega greeks)
     :greeks/theta (:theta greeks)
     :market/volume volume}))

;;; ---------------------------------------------------------------------------
;;; Strategy-Specific Generators
;;; ---------------------------------------------------------------------------

(def gen-straddle-candidate
  "Generator for options suitable for straddle analysis.

  Generates ATM options (|delta| near 0.5) with low IV."
  (gen/let [base gen-valid-option-quote
            atm-delta (gen/double* {:min 0.45 :max 0.55 :NaN? false :infinite? false})
            low-iv (gen/double* {:min 0.10 :max 0.25 :NaN? false :infinite? false})]
    (assoc base
           :greeks/delta (if (= (:option/type base) :call)
                           atm-delta
                           (- atm-delta))
           :quote/iv low-iv)))

(def gen-gamma-scalp-candidate
  "Generator for options suitable for gamma scalping.

  Generates options with high gamma relative to theta."
  (gen/let [base gen-valid-option-quote
            high-gamma (gen/double* {:min 0.1 :max 0.3 :NaN? false :infinite? false})
            low-theta (gen/double* {:min -0.5 :max -0.1 :NaN? false :infinite? false})]
    (assoc base
           :greeks/gamma high-gamma
           :greeks/theta low-theta)))

(def gen-dispersion-components
  "Generator for index components for dispersion trading."
  (gen/let [n (gen/choose 10 50)
            tickers (gen/vector gen-ticker n)
            weights (gen/vector (gen/double* {:min 0.01 :max 0.1 :NaN? false :infinite? false}) n)
            vols (gen/vector gen-iv n)]
    {:tickers tickers
     :weights (let [total (reduce + weights)]
                (mapv #(/ % total) weights))
     :vols vols}))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn sample
  "Generate n samples from a generator."
  ([gen]
   (sample gen 10))
  ([gen n]
   (gen/sample gen n)))

(defn generate
  "Generate a single value from a generator."
  [gen]
  (gen/generate gen))
