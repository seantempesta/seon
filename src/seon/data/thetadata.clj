(ns seon.data.thetadata
  "ThetaData REST API v3 client.

  Connects to Theta Terminal running locally at localhost:25503.
  Provides functions for fetching:
  - Option Greeks (EOD historical - works with STANDARD tier)
  - Option chains (expirations & strikes)
  - Stock OHLC (FREE tier)

  IMPORTANT: Greeks transformations
  - Vega is divided by 100 (ThetaData returns scaled 100x)
  - Rho is divided by 100 (ThetaData returns scaled 100x)

  Reference: https://docs.thetadata.us/"
  (:require [hato.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [seon.data.date-utils :refer [local-date->eod-instant]])
  (:import [java.time Instant LocalDate LocalDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(def base-url
  "ThetaData Terminal local server URL."
  "http://localhost:25503/v3")

(def default-timeout-ms
  "Default HTTP timeout in milliseconds.
  60 seconds for large bulk queries (SPY can have 1000s of strikes per expiration)."
  60000)

;;; ---------------------------------------------------------------------------
;;; HTTP Utilities
;;; ---------------------------------------------------------------------------

(defn- make-request
  "Make an HTTP GET request to ThetaData API.

  Args:
    endpoint - API endpoint path (e.g., '/stock/snapshot/quote')
    params - Query parameters as a map

  Returns:
    Parsed JSON response as Clojure data, or nil on error"
  [endpoint params]
  (try
    (let [url (str base-url endpoint)
          response (http/get url
                             {:query-params (assoc params :format "json")
                              :timeout default-timeout-ms
                              :as :text
                              :http-client {:redirect-policy :normal}})]
      (case (:status response)
        200 (let [body (json/parse-string (:body response) true)]
              (:response body))
        403 (do
              (log/warn "ThetaData subscription level insufficient"
                        {:endpoint endpoint
                         :message (try (json/parse-string (:body response))
                                       (catch Exception _ (:body response)))})
              nil)
        (do
          (log/error "ThetaData API request failed"
                     {:endpoint endpoint
                      :status (:status response)
                      :body (:body response)})
          nil)))
    (catch Exception e
      ;; 472 = "No data found" - expected for many requests, don't log as error
      (if (= 472 (:status (ex-data e)))
        (throw e)  ; Re-throw so caller can handle (expected case)
        (do
          (log/error e "ThetaData API request exception" {:endpoint endpoint :params params})
          nil)))))

;;; ---------------------------------------------------------------------------
;;; Data Transformations (date utilities in seon.data.date-utils)
;;; ---------------------------------------------------------------------------

(defn- parse-timestamp
  "Parse ISO timestamp string to Instant.

  Args:
    ts-str - Timestamp string in YYYY-MM-DDTHH:mm:ss.SSS format

  Returns:
    java.time.Instant or nil if parsing fails"
  [ts-str]
  (try
    (when ts-str
      ;; ThetaData returns timestamps without timezone suffix, assume UTC
      ;; Format: 2024-11-27T15:56:58.017
      (let [ts-with-z (if (str/ends-with? ts-str "Z")
                        ts-str
                        (str ts-str "Z"))]
        (Instant/parse ts-with-z)))
    (catch Exception e
      (log/warn "Failed to parse timestamp" {:timestamp ts-str :error (.getMessage e)})
      nil)))

(defn- parse-date
  "Parse date string to LocalDate.

  Args:
    date-str - Date string in YYYY-MM-DD format

  Returns:
    java.time.LocalDate or nil if parsing fails"
  [date-str]
  (try
    (when date-str
      (LocalDate/parse date-str DateTimeFormatter/ISO_LOCAL_DATE))
    (catch Exception e
      (log/warn "Failed to parse date" {:date date-str :error (.getMessage e)})
      nil)))

(defn- format-date
  "Format LocalDate or date string to YYYYMMDD format for API."
  [date]
  (cond
    (instance? LocalDate date) (.format date (DateTimeFormatter/ofPattern "yyyyMMdd"))
    (string? date) (str/replace date "-" "")
    :else (str date)))

(defn- transform-greeks-row
  "Transform a single Greeks data row from ThetaData format.

  CRITICAL: Divides vega and rho by 100 (ThetaData scaling)

  ID Format: {OCC_SYMBOL}-{ISO_TIMESTAMP}
  Example: AAPL20250117C00230000-2024-11-27T15:56:58.017Z

  Args:
    contract - Contract info map {:symbol :strike :right :expiration}
    data-row - Greeks data row from API

  Returns:
    Transformed map matching our schema with:
    - :xt/id: OCC symbol + ISO timestamp
    - :option/id: OCC symbol (e.g., AAPL20250117C00230000)
    - :option/expiry: Instant at 5pm ET on expiration date
    - :xt/valid-from: Instant at 5pm ET on quote date (for backtesting)"
  [contract data-row]
  (let [{:keys [symbol expiration strike right]} contract
        {:keys [timestamp open high low close bid ask
                delta theta vega rho epsilon lambda gamma
                implied_vol iv_error underlying_price]} data-row
        ;; Extract date from timestamp for quote date
        quote-date (when timestamp
                     (subs timestamp 0 10))
        ;; Normalize right to lowercase keyword
        right-kw (keyword (str/lower-case (str right)))
        ;; Generate OCC symbol (e.g., AAPL20250117C00230000)
        occ-symbol (str symbol
                        (str/replace (str expiration) "-" "")
                        (if (= right-kw :call) "C" "P")
                        (format "%08d" (int (* strike 1000))))
        ;; Parse timestamps and dates
        quote-instant (parse-timestamp timestamp)
        expiry-date (parse-date expiration)
        expiry-instant (when expiry-date (local-date->eod-instant expiry-date))
        quote-local-date (parse-date quote-date)
        valid-from-instant (when quote-local-date (local-date->eod-instant quote-local-date))
        ;; Generate deterministic ID: OCC symbol + ISO timestamp
        xt-id (when (and occ-symbol quote-instant)
                (str occ-symbol "-" (.toString quote-instant)))]
    (cond-> {:xt/id xt-id
             :asset/ticker symbol
             :option/id occ-symbol
             :option/strike strike
             :option/type right-kw
             :option/expiry expiry-instant
             :quote/date quote-local-date
             :quote/timestamp quote-instant}
      valid-from-instant (assoc :xt/valid-from valid-from-instant)
      close (assoc :quote/close close)
      open (assoc :quote/open open)
      high (assoc :quote/high high)
      low (assoc :quote/low low)
      bid (assoc :quote/bid bid)
      ask (assoc :quote/ask ask)
      delta (assoc :greeks/delta delta)
      gamma (assoc :greeks/gamma gamma)
      vega (assoc :greeks/vega (/ vega 100.0))  ; Scale down from 100x
      theta (assoc :greeks/theta theta)
      rho (assoc :greeks/rho (/ rho 100.0))     ; Scale down from 100x
      implied_vol (assoc :quote/iv implied_vol)
      underlying_price (assoc :underlying/price underlying_price))))

(defn- transform-greeks-response
  "Transform the nested Greeks API response.

  Response format:
  {:response [{:contract {:symbol :strike :right :expiration}
               :data [{greeks-row} ...]}
              ...]}

  Returns flat vector of transformed Greeks maps."
  [response]
  (into []
        (mapcat (fn [{:keys [contract data]}]
                  (map #(transform-greeks-row contract %) data)))
        response))

(defn- transform-stock-ohlc
  "Transform stock OHLC data from ThetaData format.

  Args:
    ohlc-data - Raw OHLC data from ThetaData API

  Returns:
    Transformed map with OHLC and timestamp"
  [ohlc-data]
  (let [{:keys [timestamp open high low close volume count vwap]} ohlc-data]
    {:timestamp (parse-timestamp timestamp)
     :open open
     :high high
     :low low
     :close close
     :volume volume
     :count count
     :vwap vwap
     :mid close}))

;;; ---------------------------------------------------------------------------
;;; Public API Functions
;;; ---------------------------------------------------------------------------

(defn fetch-option-greeks-eod
  "Fetch EOD option Greeks for a date range (works with STANDARD tier).

  Args:
    ticker - Underlying symbol (e.g., 'AAPL')
    opts - Parameters map:
      :start-date - Start date (YYYY-MM-DD or LocalDate) REQUIRED
      :end-date - End date (YYYY-MM-DD or LocalDate) REQUIRED
      :expiration - Expiration date or '*' for all (default: '*')
      :strike - Strike price or '*' for all strikes (default: '*')
      :right - Option type: 'call', 'put', or 'both' (default: 'both')

  Returns:
    Vector of option quote maps with Greeks, or nil on error

  Example:
    (fetch-option-greeks-eod 'AAPL' {:start-date '2024-11-01' :end-date '2024-11-28'})
    (fetch-option-greeks-eod 'AAPL' {:start-date '2024-11-01'
                                     :end-date '2024-11-28'
                                     :expiration '2025-01-17'
                                     :strike 230.00
                                     :right 'call'})"
  [ticker {:keys [start-date end-date expiration strike right]
           :or {expiration "*" strike "*" right "both"}}]
  (when (and start-date end-date)
    (when-let [response (make-request "/option/history/greeks/eod"
                                      {:symbol ticker
                                       :expiration (format-date expiration)
                                       :strike (str strike)
                                       :right (str right)
                                       :start_date (format-date start-date)
                                       :end_date (format-date end-date)})]
      (transform-greeks-response response))))

;; Alias for backwards compatibility
(def fetch-option-greeks fetch-option-greeks-eod)

(defn fetch-option-expirations
  "Fetch all available expiration dates for a symbol.

  Args:
    ticker - Underlying symbol (e.g., 'AAPL')

  Returns:
    Vector of expiration dates as LocalDate, or nil on error

  Example:
    (fetch-option-expirations 'AAPL')"
  [ticker]
  (when-let [response (make-request "/option/list/expirations"
                                    {:symbol ticker})]
    (->> response
         (map :expiration)
         (map parse-date)
         (remove nil?)
         vec)))

(defn fetch-option-strikes
  "Fetch all available strikes for a symbol and expiration.

  Args:
    ticker - Underlying symbol (e.g., 'AAPL')
    expiration - Expiration date (YYYY-MM-DD format or LocalDate)

  Returns:
    Vector of strike prices, or nil on error

  Example:
    (fetch-option-strikes 'AAPL' '2025-01-17')"
  [ticker expiration]
  (when-let [response (make-request "/option/list/strikes"
                                    {:symbol ticker
                                     :expiration (format-date expiration)})]
    (mapv :strike response)))

(defn fetch-option-chain
  "Fetch all strikes and expirations for a symbol on a date range.

  Args:
    ticker - Underlying symbol (e.g., 'AAPL')
    opts - Parameters map:
      :start-date - Start date (required)
      :end-date - End date (required)

  Returns:
    Vector of all option quotes with Greeks for the symbol

  Example:
    (fetch-option-chain 'AAPL' {:start-date '2024-11-01' :end-date '2024-11-28'})"
  [ticker opts]
  (fetch-option-greeks-eod ticker (merge {:expiration "*" :strike "*" :right "both"} opts)))

(defn fetch-stock-ohlc
  "Fetch stock OHLC data for a date (FREE tier).

  Args:
    ticker - Stock symbol (e.g., 'AAPL')
    date - Date to fetch (YYYY-MM-DD format or LocalDate)
    opts - Optional parameters:
      :interval - Bar size: '1m', '5m', '15m', '1h', etc. (default: '1h')

  Returns:
    Vector of OHLC bars, or nil on error

  Example:
    (fetch-stock-ohlc 'AAPL' '2024-11-27')
    (fetch-stock-ohlc 'AAPL' '2024-11-27' {:interval '5m'})"
  ([ticker date] (fetch-stock-ohlc ticker date {}))
  ([ticker date {:keys [interval] :or {interval "1h"}}]
   (when-let [response (make-request "/stock/history/ohlc"
                                     {:symbol ticker
                                      :date (format-date date)
                                      :interval interval})]
     (mapv transform-stock-ohlc response))))

(defn fetch-stock-price
  "Fetch stock closing price for a date (FREE tier).

  Uses the last OHLC bar's close price for the day.

  Args:
    ticker - Stock symbol (e.g., 'AAPL')
    date - Date to fetch (YYYY-MM-DD format or LocalDate)

  Returns:
    Closing price as double, or nil if unavailable

  Example:
    (fetch-stock-price 'AAPL' '2024-11-27')"
  [ticker date]
  (when-let [bars (fetch-stock-ohlc ticker date {:interval "1h"})]
    (when (seq bars)
      (:close (last bars)))))

;;; ---------------------------------------------------------------------------
;;; Health Check
;;; ---------------------------------------------------------------------------

(defn health-check
  "Check if ThetaData Terminal is reachable.

  Returns:
    true if Terminal is responding, false otherwise"
  []
  (try
    (let [response (http/get (str base-url "/stock/list/symbols")
                             {:query-params {:format "json"}
                              :timeout 5000
                              :as :text})]
      (= 200 (:status response)))
    (catch Exception e
      (log/error "ThetaData Terminal is not reachable" {:error (.getMessage e)})
      false)))

;;; ---------------------------------------------------------------------------
;;; Circuit Breaker
;;; ---------------------------------------------------------------------------

(def ^:private circuit-state
  "Circuit breaker state for ThetaData Terminal health.

  Fields:
    :healthy - Boolean, cached health check result
    :consecutive-failures - Count of sequential failed requests
    :last-check - Timestamp (ms) of last health check
    :circuit-opened-at - Timestamp (ms) when circuit was opened, nil if closed"
  (atom {:healthy true
         :consecutive-failures 0
         :last-check nil
         :circuit-opened-at nil}))

(def ^:private cooldown-ms
  "Circuit breaker cooldown period in milliseconds.
  After this period, the circuit will auto-recover and retry health check."
  60000) ; 60 seconds

(defn terminal-healthy?
  "Check if ThetaData Terminal is responsive. Caches result for 30 seconds.

  This function calls health-check only if the cached result is stale (>30s old).
  On health check failure, increments consecutive-failures counter.
  On success, resets consecutive-failures to 0.

  Returns:
    true if Terminal is healthy (cached or fresh), false otherwise"
  []
  (let [{:keys [last-check healthy]} @circuit-state
        now (System/currentTimeMillis)
        stale? (or (nil? last-check)
                   (> (- now last-check) 30000))]
    (if stale?
      (let [ok? (health-check)]
        (swap! circuit-state assoc
               :healthy ok?
               :last-check now
               :consecutive-failures (if ok? 0 (inc (:consecutive-failures @circuit-state))))
        ok?)
      healthy)))

(defn circuit-open?
  "Check if circuit breaker is open (terminal unavailable).

  Circuit opens after 3 consecutive failures.
  Circuit auto-recovers after cooldown-ms (60s) and resets failure counter.

  Returns:
    true if circuit is open (requests should be blocked), false otherwise"
  []
  (let [{:keys [consecutive-failures circuit-opened-at]} @circuit-state
        now (System/currentTimeMillis)]
    (cond
      ;; Circuit closed - not enough failures
      (< consecutive-failures 3)
      false

      ;; Circuit was open but cooldown expired - auto-recover
      (and circuit-opened-at (> (- now circuit-opened-at) cooldown-ms))
      (do
        (log/info "Circuit breaker auto-recovering after cooldown")
        (swap! circuit-state assoc :consecutive-failures 0 :circuit-opened-at nil)
        false)

      ;; Circuit should be open - record timestamp if not already set
      :else
      (do
        (when-not circuit-opened-at
          (log/warn "Circuit breaker OPEN after 3 consecutive failures")
          (swap! circuit-state assoc :circuit-opened-at now))
        true))))

(defn reset-circuit!
  "Manually reset circuit breaker state. Useful for testing or manual recovery.

  Resets all state to healthy defaults."
  []
  (log/info "Circuit breaker manually reset")
  (reset! circuit-state {:healthy true
                         :consecutive-failures 0
                         :last-check nil
                         :circuit-opened-at nil}))

(defn record-failure!
  "Record a failed request. Increments consecutive-failures counter.

  Should be called by with-retry after exhausting max retries.
  This allows the circuit breaker to open if failures continue."
  []
  (swap! circuit-state update :consecutive-failures inc)
  (let [failures (:consecutive-failures @circuit-state)]
    (log/warn "Request failure recorded" {:consecutive-failures failures})))

(defn circuit-status
  "Get current circuit breaker status for monitoring.

  Returns map with:
    :state - :closed, :open, or :half-open
    :healthy - Last known health status
    :consecutive-failures - Current failure count
    :circuit-opened-at - When circuit opened (nil if closed)
    :cooldown-remaining-ms - Time until auto-recovery (0 if not in cooldown)"
  []
  (let [{:keys [healthy consecutive-failures circuit-opened-at]} @circuit-state
        now (System/currentTimeMillis)
        cooldown-remaining (if circuit-opened-at
                             (max 0 (- cooldown-ms (- now circuit-opened-at)))
                             0)
        state (cond
                (< consecutive-failures 3) :closed
                (and circuit-opened-at (> (- now circuit-opened-at) cooldown-ms)) :half-open
                :else :open)]
    {:state state
     :healthy healthy
     :consecutive-failures consecutive-failures
     :circuit-opened-at circuit-opened-at
     :cooldown-remaining-ms cooldown-remaining}))

;;; ---------------------------------------------------------------------------
;;; Rate Limiting & Adaptive Delays
;;; ---------------------------------------------------------------------------

(def ^:private rate-limit-state
  "Rate limiting state for adaptive request pacing.

  Fields:
    :last-request-ms - Timestamp of last request
    :delay-ms - Current delay between requests (adaptive)
    :success-streak - Count of consecutive successful requests
    :failure-streak - Count of consecutive failed requests
    :rate-limited-at - Timestamp when rate limit was detected (nil if not limited)"
  (atom {:last-request-ms 0
         :delay-ms 100       ; Start with 100ms between requests
         :success-streak 0
         :failure-streak 0
         :rate-limited-at nil}))

(def ^:private min-delay-ms
  "Minimum delay between requests in milliseconds."
  50)

(def ^:private max-delay-ms
  "Maximum delay between requests in milliseconds (backoff cap)."
  10000)

(def ^:private rate-limit-backoff-ms
  "How long to wait after detecting rate limit (30 seconds)."
  30000)

(defn rate-limit-status
  "Get current rate limit state for monitoring.

  Returns map with:
    :delay-ms - Current delay between requests
    :success-streak - Consecutive successes
    :failure-streak - Consecutive failures
    :rate-limited - Whether currently in rate-limit backoff
    :rate-limited-remaining-ms - Time until rate limit backoff expires"
  []
  (let [{:keys [delay-ms success-streak failure-streak rate-limited-at]} @rate-limit-state
        now (System/currentTimeMillis)
        rate-limited-remaining (if rate-limited-at
                                 (max 0 (- rate-limit-backoff-ms (- now rate-limited-at)))
                                 0)]
    {:delay-ms delay-ms
     :success-streak success-streak
     :failure-streak failure-streak
     :rate-limited (and rate-limited-at (pos? rate-limited-remaining))
     :rate-limited-remaining-ms rate-limited-remaining}))

(defn- record-success!
  "Record a successful request. Adjusts delay adaptively.

  On success:
  - Increment success streak, reset failure streak
  - After 5 successes in a row, decrease delay by 10% (min 50ms)
  - Clear rate limit flag"
  []
  (swap! rate-limit-state
         (fn [{:keys [delay-ms success-streak] :as state}]
           (let [new-streak (inc success-streak)]
             (assoc state
                    :success-streak new-streak
                    :failure-streak 0
                    :rate-limited-at nil
                    ;; Decrease delay after 5 consecutive successes
                    :delay-ms (if (>= new-streak 5)
                                (max min-delay-ms (long (* delay-ms 0.9)))
                                delay-ms))))))

(defn- record-request-failure!
  "Record a failed request. Applies exponential backoff.

  On failure:
  - Increment failure streak, reset success streak
  - Double the delay (up to max-delay-ms)"
  []
  (swap! rate-limit-state
         (fn [{:keys [delay-ms] :as state}]
           (assoc state
                  :success-streak 0
                  :failure-streak (inc (:failure-streak state))
                  :delay-ms (min max-delay-ms (* delay-ms 2))))))

(defn- record-rate-limit!
  "Record a rate limit response (429 or similar).

  Sets rate-limited-at timestamp and applies aggressive backoff."
  []
  (log/warn "Rate limit detected - backing off for 30 seconds")
  (swap! rate-limit-state
         (fn [state]
           (assoc state
                  :rate-limited-at (System/currentTimeMillis)
                  :delay-ms max-delay-ms
                  :success-streak 0
                  :failure-streak (inc (:failure-streak state))))))

(defn rate-limited?
  "Check if we're currently in rate-limit backoff.

  Returns true if rate limit was detected within the last 30 seconds."
  []
  (let [{:keys [rate-limited-at]} @rate-limit-state]
    (and rate-limited-at
         (< (- (System/currentTimeMillis) rate-limited-at) rate-limit-backoff-ms))))

(defn reset-rate-limit-state!
  "Manually reset rate limit state. Useful for testing."
  []
  (reset! rate-limit-state {:last-request-ms 0
                            :delay-ms 100
                            :success-streak 0
                            :failure-streak 0
                            :rate-limited-at nil}))

(defn- wait-for-rate-limit
  "Wait until rate limit backoff expires if currently rate limited.

  Returns immediately if not rate limited.
  Logs and waits if in backoff period."
  []
  (when (rate-limited?)
    (let [{:keys [rate-limited-at]} @rate-limit-state
          remaining (- rate-limit-backoff-ms (- (System/currentTimeMillis) rate-limited-at))]
      (when (pos? remaining)
        (log/info "Waiting for rate limit backoff" {:remaining-ms remaining})
        (Thread/sleep remaining)))))

(defn- wait-for-delay
  "Wait for the adaptive delay between requests.

  Ensures minimum spacing between requests to avoid overwhelming the API."
  []
  (let [{:keys [last-request-ms delay-ms]} @rate-limit-state
        now (System/currentTimeMillis)
        elapsed (- now last-request-ms)
        wait-time (- delay-ms elapsed)]
    (when (pos? wait-time)
      (Thread/sleep wait-time))
    (swap! rate-limit-state assoc :last-request-ms (System/currentTimeMillis))))

(defn with-rate-limiting
  "Execute a function with rate limiting and adaptive delays.

  1. Waits if currently rate limited
  2. Applies adaptive delay between requests
  3. Records success/failure for adaptive adjustment

  Args:
    f - Zero-arg function to execute

  Returns:
    Result of f, or throws exception on error"
  [f]
  (wait-for-rate-limit)
  (wait-for-delay)
  (try
    (let [result (f)]
      (record-success!)
      result)
    (catch Exception e
      (let [status (:status (ex-data e))]
        (cond
          (= 429 status)
          (do
            (record-rate-limit!)
            (throw (ex-info "Rate limited by ThetaData"
                            {:type :rate-limited :status 429}
                            e)))

          (#{500 502 503 504} status)
          (do
            (record-request-failure!)
            (throw e))

          :else
          (throw e))))))

(defn fetch-with-retry
  "Fetch data with automatic retry and exponential backoff.

  Args:
    f - Zero-arg function that performs the fetch
    opts - Options map:
      :max-retries - Maximum retry attempts (default: 3)
      :on-retry - Function called on retry (fn [attempt error])

  Returns:
    Result of f, or throws after max retries exhausted"
  ([f] (fetch-with-retry f {}))
  ([f {:keys [max-retries on-retry] :or {max-retries 3}}]
   (loop [attempt 1]
     (let [result (try
                    {:success true :value (with-rate-limiting f)}
                    (catch Exception e
                      {:success false :error e}))]
       (if (:success result)
         (:value result)
         (let [{:keys [error]} result
               {:keys [type]} (ex-data error)]
           (if (and (< attempt max-retries)
                    (not= :rate-limited type))
             (do
               (when on-retry
                 (on-retry attempt error))
               (let [backoff (* 1000 (Math/pow 2 attempt))] ; 2s, 4s, 8s...
                 (log/info "Retrying after backoff" {:attempt attempt :backoff-ms backoff})
                 (Thread/sleep (long backoff)))
               (recur (inc attempt)))
             (do
               (record-failure!)
               (throw error)))))))))

(comment
  ;; Health check - verify Theta Terminal is running
  (health-check)

  ;; === Contract Discovery (FREE tier) ===

  ;; List all available expirations
  (fetch-option-expirations "AAPL")
  ;; => [#inst "2025-11-29" #inst "2025-12-06" ...]

  ;; List strikes for a specific expiration
  (fetch-option-strikes "AAPL" "2026-01-17")
  ;; => [100.0 105.0 110.0 ...]

  ;; === Stock Data (FREE tier) ===

  ;; Fetch stock OHLC bars for a day
  (fetch-stock-ohlc "AAPL" "2025-11-27")
  ;; => [{:timestamp #inst "..." :open 229.5 :close 230.0 ...}]

  ;; Fetch stock closing price
  (fetch-stock-price "AAPL" "2025-11-27")
  ;; => 230.05

  ;; === Option Greeks (STANDARD tier) ===

  ;; Fetch EOD Greeks for specific contract and date range
  (fetch-option-greeks-eod "AAPL" {:start-date "2025-11-25"
                                   :end-date "2025-11-27"
                                   :expiration "2026-01-17"
                                   :strike 230.00
                                   :right "call"})
  ;; => [{:xt/id "AAPL-..." :greeks/delta 0.52 :greeks/vega 0.45 ...}]

  ;; Fetch all options Greeks for a date range (can be large!)
  (fetch-option-chain "AAPL" {:start-date "2025-11-27"
                              :end-date "2025-11-27"})
  ;; => [{...} {...} ...] all options for AAPL on 2025-11-27

  ;; === Usage Notes ===
  ;; - STANDARD tier: Use historical/EOD endpoints (fetch-option-greeks-eod)
  ;; - PROFESSIONAL tier: Snapshot endpoints would be faster but require upgrade
  ;; - Vega and Rho are automatically scaled (divided by 100)
  ;; - Dates can be strings "YYYY-MM-DD" or LocalDate instances
  )
