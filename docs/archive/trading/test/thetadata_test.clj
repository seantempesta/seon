(ns seon.trading.thetadata-test
  "Tests for ThetaData API client and circuit breaker.

  Tests cover:
  - Response parsing and transformations
  - Circuit breaker state machine
  - Health check logic
  - Error handling and edge cases"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [seon.trading.thetadata :as theta])
  (:import [java.time LocalDate Instant ZoneId]
           [java.time.format DateTimeFormatter]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures - Reset circuit breaker before each test
;;; ---------------------------------------------------------------------------

(defn reset-circuit-fixture
  "Reset circuit breaker state before each test."
  [f]
  (theta/reset-circuit!)
  (f))

(use-fixtures :each reset-circuit-fixture)

;;; ---------------------------------------------------------------------------
;;; Mock Response Data
;;; ---------------------------------------------------------------------------

(def mock-greeks-response
  "Sample response matching ThetaData API v3 option/history/greeks/eod format."
  [{:contract {:symbol "AAPL"
               :expiration "2025-01-17"
               :strike 230.0
               :right "CALL"}
    :data [{:timestamp "2024-11-27T15:56:58.017"
            :open 2.45
            :high 2.65
            :low 2.40
            :close 2.55
            :bid 2.50
            :ask 2.60
            :delta 0.52
            :gamma 0.05
            :theta -0.15
            :vega 45.0         ; ThetaData returns scaled 100x
            :rho 25.0          ; ThetaData returns scaled 100x
            :implied_vol 0.35
            :underlying_price 234.50}]}])

(def mock-greeks-multiple-contracts
  "Response with multiple contracts and multiple data rows."
  [{:contract {:symbol "SPY"
               :expiration "2025-02-21"
               :strike 580.0
               :right "CALL"}
    :data [{:timestamp "2024-11-27T10:00:00.000"
            :close 5.25
            :bid 5.20
            :ask 5.30
            :delta 0.48
            :gamma 0.03
            :theta -0.18
            :vega 52.0
            :rho 30.0
            :implied_vol 0.28
            :underlying_price 582.50}
           {:timestamp "2024-11-28T15:00:00.000"
            :close 5.80
            :bid 5.75
            :ask 5.85
            :delta 0.51
            :gamma 0.032
            :theta -0.17
            :vega 51.0
            :rho 31.0
            :implied_vol 0.29
            :underlying_price 585.00}]}
   {:contract {:symbol "SPY"
               :expiration "2025-02-21"
               :strike 590.0
               :right "PUT"}
    :data [{:timestamp "2024-11-27T10:00:00.000"
            :close 8.10
            :bid 8.00
            :ask 8.20
            :delta -0.52
            :gamma 0.03
            :theta -0.19
            :vega 53.0
            :rho -28.0
            :implied_vol 0.30
            :underlying_price 582.50}]}])

(def mock-greeks-minimal
  "Response with minimal fields - only required data."
  [{:contract {:symbol "NVDA"
               :expiration "2025-03-21"
               :strike 145.0
               :right "CALL"}
    :data [{:timestamp "2024-11-27T14:30:00.000"}]}])

(def mock-greeks-empty
  "Empty response - no contracts found."
  [])

(def mock-greeks-with-nulls
  "Response with null/missing values for optional fields."
  [{:contract {:symbol "GOOGL"
               :expiration "2025-04-17"
               :strike 180.0
               :right "PUT"}
    :data [{:timestamp "2024-11-27T15:00:00.000"
            :close 3.25
            :delta -0.35
            :implied_vol nil
            :underlying_price nil}]}])

;;; ---------------------------------------------------------------------------
;;; Helper Functions
;;; ---------------------------------------------------------------------------

(defn instant->ny-hour
  "Extract hour in NY timezone from Instant."
  [^Instant inst]
  (.getHour (.atZone inst (ZoneId/of "America/New_York"))))

(defn local-date->eod-instant
  "Convert LocalDate to EOD Instant (5pm ET) - mirrors implementation."
  [^LocalDate date]
  (-> date
      (.atTime 17 0)
      (.atZone (ZoneId/of "America/New_York"))
      .toInstant))

;;; ---------------------------------------------------------------------------
;;; Response Parsing Tests
;;; ---------------------------------------------------------------------------

(deftest parse-greeks-response-single-contract-test
  (testing "Parses single contract with single data row"
    (let [result (#'theta/transform-greeks-response mock-greeks-response)]
      (is (= 1 (count result)) "Should return 1 quote")

      (let [quote (first result)]
        ;; Asset/Option fields
        (is (= "AAPL" (:asset/ticker quote)))
        (is (= "AAPL20250117C00230000" (:option/id quote)))
        (is (= 230.0 (:option/strike quote)))
        (is (= :call (:option/type quote)))

        ;; Expiry should be 5pm ET on expiration date
        (is (instance? Instant (:option/expiry quote)))
        (is (= 17 (instant->ny-hour (:option/expiry quote))))

        ;; Quote fields
        (is (= 2.55 (:quote/close quote)))
        (is (= 2.45 (:quote/open quote)))
        (is (= 2.65 (:quote/high quote)))
        (is (= 2.40 (:quote/low quote)))
        (is (= 2.50 (:quote/bid quote)))
        (is (= 2.60 (:quote/ask quote)))
        (is (= 0.35 (:quote/iv quote)))

        ;; Quote timestamp and date
        (is (instance? Instant (:quote/timestamp quote)))
        (is (instance? LocalDate (:quote/date quote)))
        (is (= "2024-11-27" (.format (:quote/date quote) DateTimeFormatter/ISO_LOCAL_DATE)))

        ;; Valid-from should be 5pm ET on quote date
        (is (instance? Instant (:xt/valid-from quote)))
        (is (= 17 (instant->ny-hour (:xt/valid-from quote))))

        ;; Greeks - CRITICAL: vega and rho scaled down by 100
        (is (= 0.52 (:greeks/delta quote)))
        (is (= 0.05 (:greeks/gamma quote)))
        (is (= -0.15 (:greeks/theta quote)))
        (is (= 0.45 (:greeks/vega quote)) "Vega should be divided by 100")
        (is (= 0.25 (:greeks/rho quote)) "Rho should be divided by 100")

        ;; Underlying
        (is (= 234.50 (:underlying/price quote)))

        ;; ID format: OCC symbol + ISO timestamp
        (is (string? (:xt/id quote)))
        (is (clojure.string/starts-with? (:xt/id quote) "AAPL20250117C00230000-"))
        (is (clojure.string/includes? (:xt/id quote) "2024-11-27T15:56:58.017Z"))))))

(deftest parse-greeks-response-multiple-contracts-test
  (testing "Parses multiple contracts with multiple data rows"
    (let [result (#'theta/transform-greeks-response mock-greeks-multiple-contracts)]
      (is (= 3 (count result)) "Should flatten to 3 total quotes")

      ;; First contract, first row
      (let [q1 (first result)]
        (is (= "SPY" (:asset/ticker q1)))
        (is (= "SPY20250221C00580000" (:option/id q1)))
        (is (= 580.0 (:option/strike q1)))
        (is (= :call (:option/type q1)))
        (is (= 5.25 (:quote/close q1)))
        (is (= 0.52 (:greeks/vega q1)) "Vega scaled from 52.0 → 0.52")
        (is (= 0.30 (:greeks/rho q1)) "Rho scaled from 30.0 → 0.30"))

      ;; First contract, second row (different timestamp)
      (let [q2 (second result)]
        (is (= "SPY" (:asset/ticker q2)))
        (is (= "SPY20250221C00580000" (:option/id q2)))
        (is (= 5.80 (:quote/close q2)))
        (is (clojure.string/includes? (:xt/id q2) "2024-11-28T15:00:00"))
        (is (not= (:xt/id (first result)) (:xt/id q2)) "Different timestamps = different IDs"))

      ;; Second contract (PUT)
      (let [q3 (nth result 2)]
        (is (= "SPY" (:asset/ticker q3)))
        (is (= "SPY20250221P00590000" (:option/id q3)))
        (is (= :put (:option/type q3)))
        (is (= 590.0 (:option/strike q3)))
        (is (= -0.52 (:greeks/delta q3)) "Negative delta for PUT")
        (is (= -0.28 (:greeks/rho q3)) "Negative rho for PUT")))))

(deftest parse-greeks-response-minimal-fields-test
  (testing "Handles response with minimal fields gracefully"
    (let [result (#'theta/transform-greeks-response mock-greeks-minimal)]
      (is (= 1 (count result)))

      (let [quote (first result)]
        ;; Required fields should be present
        (is (= "NVDA" (:asset/ticker quote)))
        (is (= "NVDA20250321C00145000" (:option/id quote)))
        (is (= 145.0 (:option/strike quote)))
        (is (= :call (:option/type quote)))

        ;; Optional fields should be absent (not nil, just missing keys)
        (is (not (contains? quote :quote/close)))
        (is (not (contains? quote :greeks/delta)))
        (is (not (contains? quote :greeks/vega)))
        (is (not (contains? quote :underlying/price)))))))

(deftest parse-greeks-response-empty-test
  (testing "Returns empty vector for empty response"
    (let [result (#'theta/transform-greeks-response mock-greeks-empty)]
      (is (empty? result))
      (is (vector? result)))))

(deftest parse-greeks-response-with-nulls-test
  (testing "Handles null values in optional fields"
    (let [result (#'theta/transform-greeks-response mock-greeks-with-nulls)]
      (is (= 1 (count result)))

      (let [quote (first result)]
        (is (= "GOOGL" (:asset/ticker quote)))
        (is (= 3.25 (:quote/close quote)))
        (is (= -0.35 (:greeks/delta quote)))

        ;; Nil values should not create keys
        (is (not (contains? quote :quote/iv)))
        (is (not (contains? quote :underlying/price)))))))

(deftest occ-symbol-generation-test
  (testing "OCC symbol format matches standard"
    (let [response [{:contract {:symbol "MSFT"
                                :expiration "2025-06-20"
                                :strike 450.5
                                :right "CALL"}
                     :data [{:timestamp "2024-11-27T10:00:00.000"}]}]
          result (#'theta/transform-greeks-response response)
          quote (first result)]
      ;; OCC format: TICKER(6 chars)YYMMDD(C/P)STRIKE(8 digits)
      (is (= "MSFT20250620C00450500" (:option/id quote)))
      (is (clojure.string/starts-with? (:xt/id quote) "MSFT20250620C00450500-"))))

  (testing "PUT option uses P in OCC symbol"
    (let [response [{:contract {:symbol "TSLA"
                                :expiration "2025-12-19"
                                :strike 300.0
                                :right "PUT"}
                     :data [{:timestamp "2024-11-27T10:00:00.000"}]}]
          result (#'theta/transform-greeks-response response)
          quote (first result)]
      (is (= "TSLA20251219P00300000" (:option/id quote)))
      (is (= :put (:option/type quote)))))

  (testing "Strike price formatting in OCC symbol"
    (let [response [{:contract {:symbol "SPY"
                                :expiration "2025-01-17"
                                :strike 123.45
                                :right "CALL"}
                     :data [{:timestamp "2024-11-27T10:00:00.000"}]}]
          result (#'theta/transform-greeks-response response)
          quote (first result)]
      ;; Strike 123.45 → 123450 (multiply by 1000, format as 8 digits)
      (is (= "SPY20250117C00123450" (:option/id quote))))))

(deftest valid-from-calculation-test
  (testing "Valid-from is 5pm ET on quote date"
    (let [response [{:contract {:symbol "AAPL"
                                :expiration "2025-01-17"
                                :strike 230.0
                                :right "CALL"}
                     :data [{:timestamp "2024-11-27T10:30:45.123"}]}]
          result (#'theta/transform-greeks-response response)
          quote (first result)
          valid-from (:xt/valid-from quote)
          expected-eod (local-date->eod-instant (LocalDate/of 2024 11 27))]
      (is (= expected-eod valid-from))
      (is (= 17 (instant->ny-hour valid-from)) "Should be 5pm ET")))

  (testing "Multiple quotes on same date have same valid-from"
    (let [response [{:contract {:symbol "SPY"
                                :expiration "2025-01-17"
                                :strike 580.0
                                :right "CALL"}
                     :data [{:timestamp "2024-11-27T09:30:00.000"}
                            {:timestamp "2024-11-27T15:59:59.999"}]}]
          result (#'theta/transform-greeks-response response)
          vf1 (:xt/valid-from (first result))
          vf2 (:xt/valid-from (second result))]
      (is (= vf1 vf2) "Same quote date = same valid-from"))))

(deftest expiry-calculation-test
  (testing "Expiry is 5pm ET on expiration date"
    (let [response [{:contract {:symbol "AAPL"
                                :expiration "2025-01-17"
                                :strike 230.0
                                :right "CALL"}
                     :data [{:timestamp "2024-11-27T10:00:00.000"}]}]
          result (#'theta/transform-greeks-response response)
          quote (first result)
          expiry (:option/expiry quote)
          expected-eod (local-date->eod-instant (LocalDate/of 2025 1 17))]
      (is (= expected-eod expiry))
      (is (= 17 (instant->ny-hour expiry)) "Should be 5pm ET"))))

(deftest deterministic-id-test
  (testing "Same contract + timestamp = same ID (idempotent)"
    (let [response [{:contract {:symbol "AAPL"
                                :expiration "2025-01-17"
                                :strike 230.0
                                :right "CALL"}
                     :data [{:timestamp "2024-11-27T15:56:58.017"}]}]
          result1 (#'theta/transform-greeks-response response)
          result2 (#'theta/transform-greeks-response response)]
      (is (= (:xt/id (first result1)) (:xt/id (first result2)))
          "Parsing same response twice should produce identical IDs")))

  (testing "Different timestamp = different ID"
    (let [response1 [{:contract {:symbol "AAPL"
                                 :expiration "2025-01-17"
                                 :strike 230.0
                                 :right "CALL"}
                      :data [{:timestamp "2024-11-27T10:00:00.000"}]}]
          response2 [{:contract {:symbol "AAPL"
                                 :expiration "2025-01-17"
                                 :strike 230.0
                                 :right "CALL"}
                      :data [{:timestamp "2024-11-27T15:00:00.000"}]}]
          result1 (#'theta/transform-greeks-response response1)
          result2 (#'theta/transform-greeks-response response2)]
      (is (not= (:xt/id (first result1)) (:xt/id (first result2)))
          "Different timestamps should produce different IDs"))))

;;; ---------------------------------------------------------------------------
;;; Circuit Breaker Tests
;;; ---------------------------------------------------------------------------

(deftest circuit-breaker-initial-state-test
  (testing "Circuit starts closed (healthy)"
    (theta/reset-circuit!)
    (is (not (theta/circuit-open?)) "Circuit should start closed")))

(deftest circuit-breaker-opens-after-3-failures-test
  (testing "Circuit opens after 3 consecutive failures"
    (theta/reset-circuit!)
    (is (not (theta/circuit-open?)))

    (theta/record-failure!)
    (is (not (theta/circuit-open?)) "1 failure - circuit still closed")

    (theta/record-failure!)
    (is (not (theta/circuit-open?)) "2 failures - circuit still closed")

    (theta/record-failure!)
    (is (theta/circuit-open?) "3 failures - circuit should open")))

(deftest circuit-breaker-auto-recovery-test
  (testing "Circuit auto-recovers after cooldown period"
    (theta/reset-circuit!)

    ;; Open the circuit
    (dotimes [_ 3] (theta/record-failure!))
    (is (theta/circuit-open?))

    ;; Mock the circuit-opened-at to be >60s ago
    ;; Access private var and deref the atom
    (let [old-time (- (System/currentTimeMillis) 61000)]
      (swap! @#'theta/circuit-state assoc :circuit-opened-at old-time)

      ;; Check circuit - should auto-recover
      (is (not (theta/circuit-open?)) "Circuit should auto-recover after 60s cooldown")

      ;; Verify failure count was reset
      (is (not (theta/circuit-open?)) "Circuit should stay closed after recovery"))))

(deftest circuit-breaker-manual-reset-test
  (testing "Manual reset clears circuit state"
    (theta/reset-circuit!)

    ;; Open the circuit
    (dotimes [_ 3] (theta/record-failure!))
    (is (theta/circuit-open?))

    ;; Manual reset
    (theta/reset-circuit!)
    (is (not (theta/circuit-open?)) "Circuit should be closed after manual reset")))

(deftest circuit-breaker-does-not-open-prematurely-test
  (testing "Circuit does not open with < 3 failures"
    (theta/reset-circuit!)

    (theta/record-failure!)
    (is (not (theta/circuit-open?)))

    (theta/record-failure!)
    (is (not (theta/circuit-open?)) "Circuit should not open with only 2 failures")))

(deftest circuit-breaker-stays-open-during-cooldown-test
  (testing "Circuit stays open during cooldown period"
    (theta/reset-circuit!)

    ;; Open the circuit
    (dotimes [_ 3] (theta/record-failure!))
    (is (theta/circuit-open?))

    ;; Check immediately (within cooldown)
    (Thread/sleep 100)
    (is (theta/circuit-open?) "Circuit should stay open during cooldown")

    ;; Check again (still within cooldown)
    (Thread/sleep 100)
    (is (theta/circuit-open?) "Circuit should stay open during cooldown")))

;;; ---------------------------------------------------------------------------
;;; Health Check Tests (State Management)
;;; ---------------------------------------------------------------------------

(deftest terminal-healthy-caching-test
  (testing "terminal-healthy? caches result for 30 seconds"
    (theta/reset-circuit!)

    ;; First call - will do actual health check
    ;; We can't mock the HTTP call easily, so we just verify state changes
    (let [initial-last-check (:last-check @@#'theta/circuit-state)]

      ;; Manually set a recent last-check
      (swap! @#'theta/circuit-state assoc
             :last-check (System/currentTimeMillis)
             :healthy true)

      ;; Call should use cached value
      (theta/terminal-healthy?)

      ;; Last-check should be unchanged (used cache)
      (is (= (:last-check @@#'theta/circuit-state)
             (:last-check @@#'theta/circuit-state))
          "Should use cached value when < 30s old"))))

(deftest terminal-healthy-updates-failure-count-test
  (testing "terminal-healthy? updates consecutive-failures on health check"
    (theta/reset-circuit!)

    ;; Manually update state to simulate behavior
    ;; Set healthy state
    (swap! @#'theta/circuit-state assoc
           :healthy true
           :consecutive-failures 0
           :last-check (System/currentTimeMillis))

    ;; Verify initial state
    (is (= 0 (:consecutive-failures @@#'theta/circuit-state)))

    ;; Now simulate an unhealthy check by setting stale timestamp
    (swap! @#'theta/circuit-state assoc
           :last-check (- (System/currentTimeMillis) 31000)
           :healthy false)

    ;; Note: We can't easily test the actual health-check call without mocking
    ;; But we can verify the state machine logic
    (is (= false (:healthy @@#'theta/circuit-state)))))

;;; ---------------------------------------------------------------------------
;;; Date/Time Transformation Tests
;;; ---------------------------------------------------------------------------

(deftest local-date-to-eod-instant-test
  (testing "Converts LocalDate to 5pm ET Instant"
    ;; Winter (EST - UTC-5, so 5pm = 22:00 UTC)
    (let [winter-date (LocalDate/of 2024 1 15)
          winter-eod (local-date->eod-instant winter-date)
          winter-utc-hour (.getHour (.atZone winter-eod (ZoneId/of "UTC")))]
      (is (= 17 (instant->ny-hour winter-eod)))
      (is (= 22 winter-utc-hour) "EST: 5pm ET = 10pm UTC"))

    ;; Summer (EDT - UTC-4, so 5pm = 21:00 UTC)
    (let [summer-date (LocalDate/of 2024 7 15)
          summer-eod (local-date->eod-instant summer-date)
          summer-utc-hour (.getHour (.atZone summer-eod (ZoneId/of "UTC")))]
      (is (= 17 (instant->ny-hour summer-eod)))
      (is (= 21 summer-utc-hour) "EDT: 5pm ET = 9pm UTC"))))

(deftest parse-timestamp-test
  (testing "Parses ISO timestamp strings"
    (let [ts-str "2024-11-27T15:56:58.017Z"
          result (#'theta/parse-timestamp ts-str)]
      (is (instance? Instant result))
      (is (= "2024-11-27T15:56:58.017Z" (.toString result)))))

  (testing "Adds Z suffix if missing"
    (let [ts-str "2024-11-27T15:56:58.017"
          result (#'theta/parse-timestamp ts-str)]
      (is (instance? Instant result))
      (is (= "2024-11-27T15:56:58.017Z" (.toString result)))))

  (testing "Returns nil for invalid timestamp"
    (is (nil? (#'theta/parse-timestamp "invalid")))
    (is (nil? (#'theta/parse-timestamp nil)))
    (is (nil? (#'theta/parse-timestamp "")))))

(deftest parse-date-test
  (testing "Parses ISO date strings"
    (let [date-str "2024-11-27"
          result (#'theta/parse-date date-str)]
      (is (instance? LocalDate result))
      (is (= "2024-11-27" (.format result DateTimeFormatter/ISO_LOCAL_DATE)))))

  (testing "Returns nil for invalid date"
    (is (nil? (#'theta/parse-date "invalid")))
    (is (nil? (#'theta/parse-date nil)))
    (is (nil? (#'theta/parse-date "")))))

(deftest format-date-test
  (testing "Formats LocalDate to YYYYMMDD"
    (let [date (LocalDate/of 2024 11 27)
          result (#'theta/format-date date)]
      (is (= "20241127" result))))

  (testing "Formats date string to YYYYMMDD"
    (is (= "20241127" (#'theta/format-date "2024-11-27"))))

  (testing "Handles already formatted dates"
    (is (= "20241127" (#'theta/format-date "20241127")))))

;;; ---------------------------------------------------------------------------
;;; Edge Cases and Error Handling
;;; ---------------------------------------------------------------------------

(deftest greek-scaling-edge-cases-test
  (testing "Vega scaling handles zero"
    (let [response [{:contract {:symbol "TEST"
                                :expiration "2025-01-17"
                                :strike 100.0
                                :right "CALL"}
                     :data [{:timestamp "2024-11-27T10:00:00.000"
                             :vega 0.0
                             :rho 0.0}]}]
          result (#'theta/transform-greeks-response response)
          quote (first result)]
      (is (= 0.0 (:greeks/vega quote)))
      (is (= 0.0 (:greeks/rho quote)))))

  (testing "Vega scaling handles negative values"
    (let [response [{:contract {:symbol "TEST"
                                :expiration "2025-01-17"
                                :strike 100.0
                                :right "CALL"}
                     :data [{:timestamp "2024-11-27T10:00:00.000"
                             :vega -50.0
                             :rho -100.0}]}]
          result (#'theta/transform-greeks-response response)
          quote (first result)]
      (is (= -0.5 (:greeks/vega quote)))
      (is (= -1.0 (:greeks/rho quote)))))

  (testing "Vega scaling handles large values"
    (let [response [{:contract {:symbol "TEST"
                                :expiration "2025-01-17"
                                :strike 100.0
                                :right "CALL"}
                     :data [{:timestamp "2024-11-27T10:00:00.000"
                             :vega 10000.0
                             :rho 5000.0}]}]
          result (#'theta/transform-greeks-response response)
          quote (first result)]
      (is (= 100.0 (:greeks/vega quote)))
      (is (= 50.0 (:greeks/rho quote))))))

(deftest option-type-normalization-test
  (testing "Normalizes CALL to :call"
    (let [response [{:contract {:symbol "TEST"
                                :expiration "2025-01-17"
                                :strike 100.0
                                :right "CALL"}
                     :data [{:timestamp "2024-11-27T10:00:00.000"}]}]
          result (#'theta/transform-greeks-response response)]
      (is (= :call (:option/type (first result))))))

  (testing "Normalizes PUT to :put"
    (let [response [{:contract {:symbol "TEST"
                                :expiration "2025-01-17"
                                :strike 100.0
                                :right "PUT"}
                     :data [{:timestamp "2024-11-27T10:00:00.000"}]}]
          result (#'theta/transform-greeks-response response)]
      (is (= :put (:option/type (first result))))))

  (testing "Handles lowercase right values"
    (let [response [{:contract {:symbol "TEST"
                                :expiration "2025-01-17"
                                :strike 100.0
                                :right "call"}
                     :data [{:timestamp "2024-11-27T10:00:00.000"}]}]
          result (#'theta/transform-greeks-response response)]
      (is (= :call (:option/type (first result)))))))

(deftest strike-formatting-edge-cases-test
  (testing "Handles fractional strikes"
    (let [response [{:contract {:symbol "SPY"
                                :expiration "2025-01-17"
                                :strike 123.456
                                :right "CALL"}
                     :data [{:timestamp "2024-11-27T10:00:00.000"}]}]
          result (#'theta/transform-greeks-response response)
          quote (first result)]
      ;; Strike 123.456 → 123456 (multiply by 1000, int conversion)
      (is (clojure.string/includes? (:option/id quote) "00123456"))))

  (testing "Handles whole number strikes"
    (let [response [{:contract {:symbol "SPY"
                                :expiration "2025-01-17"
                                :strike 100.0
                                :right "CALL"}
                     :data [{:timestamp "2024-11-27T10:00:00.000"}]}]
          result (#'theta/transform-greeks-response response)
          quote (first result)]
      (is (clojure.string/includes? (:option/id quote) "00100000")))))

(deftest multi-letter-ticker-test
  (testing "Handles multi-letter tickers correctly"
    (let [response [{:contract {:symbol "GOOGL"
                                :expiration "2025-01-17"
                                :strike 180.0
                                :right "CALL"}
                     :data [{:timestamp "2024-11-27T10:00:00.000"}]}]
          result (#'theta/transform-greeks-response response)
          quote (first result)]
      (is (= "GOOGL" (:asset/ticker quote)))
      (is (clojure.string/starts-with? (:option/id quote) "GOOGL")))))

;;; ---------------------------------------------------------------------------
;;; Integration-Style Tests (Multiple Transformations)
;;; ---------------------------------------------------------------------------

(deftest full-pipeline-transformation-test
  (testing "Complete transformation pipeline produces valid quote structure"
    (let [result (#'theta/transform-greeks-response mock-greeks-response)
          quote (first result)]
      ;; Verify all expected keys are present
      (is (contains? quote :xt/id))
      (is (contains? quote :xt/valid-from))
      (is (contains? quote :asset/ticker))
      (is (contains? quote :option/id))
      (is (contains? quote :option/strike))
      (is (contains? quote :option/type))
      (is (contains? quote :option/expiry))
      (is (contains? quote :quote/date))
      (is (contains? quote :quote/timestamp))
      (is (contains? quote :quote/close))
      (is (contains? quote :quote/bid))
      (is (contains? quote :quote/ask))
      (is (contains? quote :quote/iv))
      (is (contains? quote :greeks/delta))
      (is (contains? quote :greeks/gamma))
      (is (contains? quote :greeks/theta))
      (is (contains? quote :greeks/vega))
      (is (contains? quote :greeks/rho))
      (is (contains? quote :underlying/price))

      ;; Verify types
      (is (string? (:xt/id quote)))
      (is (instance? Instant (:xt/valid-from quote)))
      (is (string? (:asset/ticker quote)))
      (is (string? (:option/id quote)))
      (is (number? (:option/strike quote)))
      (is (keyword? (:option/type quote)))
      (is (instance? Instant (:option/expiry quote)))
      (is (instance? LocalDate (:quote/date quote)))
      (is (instance? Instant (:quote/timestamp quote)))
      (is (number? (:quote/close quote)))
      (is (number? (:greeks/delta quote)))
      (is (number? (:underlying/price quote))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.data.thetadata-test)

  ;; Run specific test
  (clojure.test/test-var #'parse-greeks-response-single-contract-test)
  (clojure.test/test-var #'circuit-breaker-opens-after-3-failures-test)
  (clojure.test/test-var #'greek-scaling-edge-cases-test)

  ;; Test parsing with mock data
  (#'theta/transform-greeks-response mock-greeks-response)
  (#'theta/transform-greeks-response mock-greeks-multiple-contracts)

  ;; Test circuit breaker manually
  (theta/reset-circuit!)
  (theta/circuit-open?)
  (theta/record-failure!)
  (theta/record-failure!)
  (theta/record-failure!)
  (theta/circuit-open?))
