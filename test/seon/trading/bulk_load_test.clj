(ns seon.trading.bulk-load-test
  "Tests for bulk loading pipeline.

  Tests cover:
  - import-status: Verify correct structure and all keys
  - process-daily-items!: Mock fetch and ingest to test flow
  - resilient-bulk-load!: Test resumption by verifying it skips completed dates
  - bulk-load-from-repl!: Test preconditions

  Uses mocking to avoid actual database/API calls. Integration tests
  with real XTDB nodes are in separate integration test files."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [seon.trading.bulk-load :as bulk-load]
            [seon.trading.ingest :as ingest]
            [seon.trading.thetadata :as theta]
            [seon.trading.ingestion-state :as state]
            [seon.trading.date-utils :as date-utils]
            [seon.db.node :as node])
  (:import [java.time LocalDate Instant]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(defn reset-circuit-fixture
  "Reset circuit breaker before each test."
  [f]
  (theta/reset-circuit!)
  (f))

(use-fixtures :each reset-circuit-fixture)

;;; ---------------------------------------------------------------------------
;;; Mock Data
;;; ---------------------------------------------------------------------------

(def mock-option-quote
  "Sample option quote matching our schema."
  {:xt/id "AAPL20250117C00230000-2024-11-27T15:56:58.017Z"
   :xt/valid-from #inst "2024-11-27T22:00:00Z"
   :asset/ticker "AAPL"
   :option/id "AAPL20250117C00230000"
   :option/strike 230.0
   :option/type :call
   :option/expiry #inst "2025-01-17T22:00:00Z"
   :quote/date (LocalDate/of 2024 11 27)
   :quote/timestamp #inst "2024-11-27T15:56:58.017Z"
   :quote/iv 0.35
   :quote/bid 2.50
   :quote/ask 2.60
   :quote/close 2.55
   :greeks/delta 0.52
   :greeks/gamma 0.05
   :greeks/theta -0.15
   :greeks/vega 0.45
   :greeks/rho 0.25
   :underlying/price 234.50})

(def mock-option-quote-2
  "Second option quote for testing batches."
  {:xt/id "AAPL20250117P00220000-2024-11-27T15:56:58.017Z"
   :xt/valid-from #inst "2024-11-27T22:00:00Z"
   :asset/ticker "AAPL"
   :option/id "AAPL20250117P00220000"
   :option/strike 220.0
   :option/type :put
   :option/expiry #inst "2025-01-17T22:00:00Z"
   :quote/date (LocalDate/of 2024 11 27)
   :quote/timestamp #inst "2024-11-27T15:56:58.017Z"
   :quote/iv 0.38
   :quote/bid 3.20
   :quote/ask 3.30
   :quote/close 3.25
   :greeks/delta -0.48
   :greeks/gamma 0.05
   :greeks/theta -0.16
   :greeks/vega 0.46
   :greeks/rho -0.23
   :underlying/price 234.50})

(defn make-mock-node
  "Create a mock XTDB node for testing.

  Returns a map that captures transactions and query calls for verification."
  []
  (let [txs (atom [])
        queries (atom [])]
    ^{:txs txs :queries queries}
    (reify
      Object
      (toString [_] "MockNode")

      ;; Capture transactions
      clojure.lang.IFn
      (invoke [_ query-or-tx]
        (cond
          (vector? query-or-tx)
          (do (swap! txs conj query-or-tx)
              {:tx-id (count @txs)})

          :else
          (do (swap! queries conj query-or-tx)
              [])))

      (invoke [_ query opts]
        (swap! queries conj [query opts])
        []))))

;;; ---------------------------------------------------------------------------
;;; import-status Tests
;;; ---------------------------------------------------------------------------

(deftest import-status-structure-test
  (testing "Returns map with all required keys"
    (with-redefs [state/list-in-progress (fn [_] [])
                  state/list-all-states (fn [_] [])
                  node/query (fn [_ _] [{:count 0}])
                  theta/circuit-status (fn [] {:state :closed :consecutive-failures 0})
                  theta/rate-limit-status (fn [] {:requests-remaining 100})]
      (let [mock-node (make-mock-node)
            result (bulk-load/import-status mock-node)]

        ;; Verify all expected keys are present
        (is (contains? result :symbols-in-progress))
        (is (contains? result :all-states))
        (is (contains? result :total-option-records))
        (is (contains? result :circuit-breaker))
        (is (contains? result :rate-limit))
        (is (contains? result :memory))

        ;; Verify types
        (is (vector? (:symbols-in-progress result)))
        (is (vector? (:all-states result)))
        (is (number? (:total-option-records result)))
        (is (map? (:circuit-breaker result)))
        (is (map? (:rate-limit result)))
        (is (map? (:memory result)))))))

(deftest import-status-memory-fields-test
  (testing "Memory stats include expected fields"
    (with-redefs [state/list-in-progress (fn [_] [])
                  state/list-all-states (fn [_] [])
                  node/query (fn [_ _] [{:count 0}])
                  theta/circuit-status (fn [] {:state :closed})
                  theta/rate-limit-status (fn [] {})]
      (let [mock-node (make-mock-node)
            result (bulk-load/import-status mock-node)
            memory (:memory result)]

        (is (contains? memory :used-mb))
        (is (contains? memory :max-mb))
        (is (contains? memory :free-mb))
        (is (number? (:used-mb memory)))
        (is (number? (:max-mb memory)))
        (is (number? (:free-mb memory)))))))

(deftest import-status-filters-state-fields-test
  (testing "Filters in-progress symbols to relevant fields only"
    (with-redefs [state/list-in-progress
                  (fn [_] [{:ingestion/symbol "SPY"
                            :ingestion/last-date #inst "2024-11-27"
                            :ingestion/records-count 1000
                            :ingestion/updated-at #inst "2024-11-28"
                            :ingestion/status :in-progress
                            :xt/id "ingestion-state-SPY"}])
                  state/list-all-states (fn [_] [])
                  node/query (fn [_ _] [{:count 0}])
                  theta/circuit-status (fn [] {:state :closed})
                  theta/rate-limit-status (fn [] {})]
      (let [mock-node (make-mock-node)
            result (bulk-load/import-status mock-node)
            in-progress (first (:symbols-in-progress result))]

        ;; Should include these fields
        (is (= "SPY" (:ingestion/symbol in-progress)))
        (is (= #inst "2024-11-27" (:ingestion/last-date in-progress)))
        (is (= 1000 (:ingestion/records-count in-progress)))
        (is (= #inst "2024-11-28" (:ingestion/updated-at in-progress)))

        ;; Should NOT include these fields (filtered out)
        (is (not (contains? in-progress :ingestion/status)))
        (is (not (contains? in-progress :xt/id)))))))

(deftest import-status-counts-records-test
  (testing "Queries and returns total option records count"
    (with-redefs [state/list-in-progress (fn [_] [])
                  state/list-all-states (fn [_] [])
                  node/query (fn [_ _] [{:count 123456}])
                  theta/circuit-status (fn [] {:state :closed})
                  theta/rate-limit-status (fn [] {})]
      (let [mock-node (make-mock-node)
            result (bulk-load/import-status mock-node)]

        (is (= 123456 (:total-option-records result)))))))

(deftest import-status-handles-nil-count-test
  (testing "Handles nil count gracefully"
    (with-redefs [state/list-in-progress (fn [_] [])
                  state/list-all-states (fn [_] [])
                  node/query (fn [_ _] [{:count nil}])
                  theta/circuit-status (fn [] {:state :closed})
                  theta/rate-limit-status (fn [] {})]
      (let [mock-node (make-mock-node)
            result (bulk-load/import-status mock-node)]

        (is (= 0 (:total-option-records result)))))))

;;; ---------------------------------------------------------------------------
;;; process-daily-items! Tests
;;; ---------------------------------------------------------------------------

(deftest process-daily-items-successful-fetch-test
  (testing "Processes successful fetch and validates, ingests, and checkpoints"
    (let [txs (atom [])
          checkpoints (atom [])
          mock-node (make-mock-node)
          items [{:symbol "AAPL" :date (LocalDate/of 2024 11 27) :status :pending}]]

      (with-redefs [;; Mock circuit breaker
                    theta/circuit-open? (constantly false)

                    ;; Mock fetch - returns successful result
                    ingest/execute-daily-work-item!
                    (fn [item]
                      {:symbol "AAPL"
                       :date (LocalDate/of 2024 11 27)
                       :status :fetched
                       :data [mock-option-quote mock-option-quote-2]
                       :records 2})

                    ;; Mock transform - pass through
                    ingest/thetadata->xtdb-doc identity

                    ;; Capture ingest calls
                    ingest/ingest-batch!
                    (fn [node docs vf]
                      (swap! txs conj {:docs docs :valid-from vf})
                      nil)

                    ;; Capture checkpoint calls
                    state/mark-date-done!
                    (fn [node symbol date count]
                      (swap! checkpoints conj {:symbol symbol :date date :count count})
                      nil)]

        (let [{:keys [results stats]} (bulk-load/process-daily-items!
                                       mock-node "AAPL" items {:parallelism 1})]

          ;; Verify results
          (is (= 1 (count results)))
          (is (= :fetched (:status (first results))))
          (is (= "AAPL" (:symbol (first results))))

          ;; Verify stats
          (is (= 2 (:records stats)))
          (is (= 1 (:fetched stats)))
          (is (= 0 (:errors stats)))
          (is (= 0 (:no-data stats)))

          ;; Verify ingest was called with transformed docs
          (is (= 1 (count @txs)))
          (is (= 2 (count (:docs (first @txs)))))

          ;; Verify checkpoint was called
          (is (= 1 (count @checkpoints)))
          (is (= "AAPL" (:symbol (first @checkpoints))))
          (is (= (LocalDate/of 2024 11 27) (:date (first @checkpoints))))
          (is (= 2 (:count (first @checkpoints)))))))))

(deftest process-daily-items-no-data-test
  (testing "Handles no-data status and checkpoints with zero records"
    (let [checkpoints (atom [])
          mock-node (make-mock-node)
          items [{:symbol "AAPL" :date (LocalDate/of 2024 1 1) :status :pending}]]

      (with-redefs [theta/circuit-open? (constantly false)
                    ingest/execute-daily-work-item!
                    (fn [_] {:symbol "AAPL"
                             :date (LocalDate/of 2024 1 1)
                             :status :no-data})
                    state/mark-date-done!
                    (fn [node symbol date count]
                      (swap! checkpoints conj {:symbol symbol :date date :count count}))]

        (let [{:keys [results stats]} (bulk-load/process-daily-items!
                                       mock-node "AAPL" items {:parallelism 1})]

          ;; Verify stats
          (is (= 0 (:records stats)))
          (is (= 0 (:fetched stats)))
          (is (= 1 (:no-data stats)))
          (is (= 0 (:errors stats)))

          ;; Verify checkpoint was still called with 0 records
          (is (= 1 (count @checkpoints)))
          (is (= 0 (:count (first @checkpoints)))))))))

(deftest process-daily-items-failed-fetch-test
  (testing "Handles failed fetch and tracks error stats"
    (let [mock-node (make-mock-node)
          items [{:symbol "AAPL" :date (LocalDate/of 2024 11 27) :status :pending}]]

      (with-redefs [theta/circuit-open? (constantly false)
                    ingest/execute-daily-work-item!
                    (fn [_] {:symbol "AAPL"
                             :date (LocalDate/of 2024 11 27)
                             :status :failed
                             :error "API timeout"})]

        (let [{:keys [results stats]} (bulk-load/process-daily-items!
                                       mock-node "AAPL" items {:parallelism 1})]

          ;; Verify stats
          (is (= 0 (:records stats)))
          (is (= 0 (:fetched stats)))
          (is (= 1 (:errors stats)))
          (is (= 0 (:no-data stats)))

          ;; Verify result includes error
          (is (= :failed (:status (first results))))
          (is (= "API timeout" (:error (first results)))))))))

(deftest process-daily-items-circuit-open-test
  (testing "Throws circuit-open exception when circuit breaker opens"
    (let [mock-node (make-mock-node)
          items [{:symbol "AAPL" :date (LocalDate/of 2024 11 27) :status :pending}]]

      (with-redefs [theta/circuit-open? (constantly true)]

        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Circuit breaker open"
             (bulk-load/process-daily-items! mock-node "AAPL" items {:parallelism 1})))

        ;; Verify exception data
        (try
          (bulk-load/process-daily-items! mock-node "AAPL" items {:parallelism 1})
          (catch clojure.lang.ExceptionInfo e
            (let [data (ex-data e)]
              (is (= :circuit-open (:type data)))
              (is (= "AAPL" (:symbol data)))
              (is (contains? data :stats)))))))))

(deftest process-daily-items-batching-test
  (testing "Processes items in batches according to parallelism"
    (let [fetch-calls (atom [])
          mock-node (make-mock-node)
          items (vec (for [day (range 1 6)]
                       {:symbol "AAPL"
                        :date (LocalDate/of 2024 11 day)
                        :status :pending}))]

      (with-redefs [theta/circuit-open? (constantly false)
                    ingest/execute-daily-work-item!
                    (fn [item]
                      (swap! fetch-calls conj item)
                      (assoc item :status :no-data))
                    state/mark-date-done! (fn [_ _ _ _] nil)]

        (bulk-load/process-daily-items! mock-node "AAPL" items {:parallelism 2})

        ;; All 5 items should have been fetched
        (is (= 5 (count @fetch-calls)))))))

(deftest process-daily-items-removes-data-field-test
  (testing "Results exclude :data field to save memory"
    (let [mock-node (make-mock-node)
          items [{:symbol "AAPL" :date (LocalDate/of 2024 11 27) :status :pending}]]

      (with-redefs [theta/circuit-open? (constantly false)
                    ingest/execute-daily-work-item!
                    (fn [_] {:symbol "AAPL"
                             :date (LocalDate/of 2024 11 27)
                             :status :fetched
                             :data [mock-option-quote]
                             :records 1})
                    ingest/thetadata->xtdb-doc identity
                    ingest/ingest-batch! (fn [_ _ _] nil)
                    state/mark-date-done! (fn [_ _ _ _] nil)]

        (let [{:keys [results]} (bulk-load/process-daily-items!
                                 mock-node "AAPL" items {:parallelism 1})]

          ;; Result should not contain :data field
          (is (not (contains? (first results) :data)))
          (is (= :fetched (:status (first results))))
          (is (= 1 (:records (first results)))))))))

;;; ---------------------------------------------------------------------------
;;; resilient-bulk-load! Tests
;;; ---------------------------------------------------------------------------

(deftest resilient-bulk-load-skips-completed-dates-test
  (testing "Skips dates that are already completed"
    (let [plan-calls (atom [])
          mock-node (make-mock-node)
          symbols ["AAPL"]
          start-date (LocalDate/of 2024 11 25)
          end-date (LocalDate/of 2024 11 27)
          completed-dates #{(LocalDate/of 2024 11 26)}]

      (with-redefs [;; Mock state query - return completed dates
                    state/get-completed-dates (fn [_ _] completed-dates)

                    ;; Capture work planning
                    ingest/plan-daily-work
                    (fn [symbol start end completed]
                      (swap! plan-calls conj {:symbol symbol
                                              :start start
                                              :end end
                                              :completed completed})
                      ;; Return work items (simulating 2 days to process)
                      [{:symbol symbol :date (LocalDate/of 2024 11 25) :status :pending}
                       {:symbol symbol :date (LocalDate/of 2024 11 27) :status :pending}])

                    ;; Mock processing
                    bulk-load/process-daily-items!
                    (fn [_ _ items _]
                      {:results (mapv #(assoc % :status :fetched) items)
                       :stats {:records 10 :fetched 2 :no-data 0 :errors 0}})]

        (let [result (bulk-load/resilient-bulk-load!
                      mock-node symbols start-date end-date {:parallelism 1})]

          ;; Verify plan-daily-work was called with completed dates
          (is (= 1 (count @plan-calls)))
          (is (= completed-dates (:completed (first @plan-calls))))

          ;; Verify success
          (is (:success result))
          (is (= 1 (:symbols-completed (:stats result))))
          (is (= 0 (:symbols-failed (:stats result)))))))))

(deftest resilient-bulk-load-all-completed-test
  (testing "Handles case where all days are already completed"
    (let [mock-node (make-mock-node)
          symbols ["AAPL"]
          start-date (LocalDate/of 2024 11 25)
          end-date (LocalDate/of 2024 11 27)]

      (with-redefs [state/get-completed-dates (fn [_ _] #{})

                    ;; Return empty work items (all completed)
                    ingest/plan-daily-work (fn [_ _ _ _] [])

                    ;; Should not be called
                    bulk-load/process-daily-items!
                    (fn [_ _ _ _]
                      (throw (ex-info "Should not process when no work" {})))]

        (let [result (bulk-load/resilient-bulk-load!
                      mock-node symbols start-date end-date {:parallelism 1})]

          ;; Verify success even though nothing was processed
          (is (:success result))
          (is (= 1 (:symbols-completed (:stats result))))
          (is (= 0 (:total-records (:stats result)))))))))

(deftest resilient-bulk-load-multiple-symbols-test
  (testing "Processes multiple symbols sequentially"
    (let [processed-symbols (atom [])
          mock-node (make-mock-node)
          symbols ["AAPL" "SPY" "NVDA"]
          start-date (LocalDate/of 2024 11 27)
          end-date (LocalDate/of 2024 11 27)]

      (with-redefs [state/get-completed-dates (fn [_ _] #{})
                    ingest/plan-daily-work
                    (fn [symbol _ _ _]
                      (swap! processed-symbols conj symbol)
                      [{:symbol symbol :date start-date :status :pending}])
                    bulk-load/process-daily-items!
                    (fn [_ _ items _]
                      {:results (mapv #(assoc % :status :fetched) items)
                       :stats {:records 5 :fetched 1 :no-data 0 :errors 0}})]

        (let [result (bulk-load/resilient-bulk-load!
                      mock-node symbols start-date end-date {:parallelism 1})]

          ;; All symbols should be processed
          (is (= ["AAPL" "SPY" "NVDA"] @processed-symbols))
          (is (= 3 (:symbols-completed (:stats result))))
          (is (= 15 (:total-records (:stats result))))
          (is (:success result)))))))

(deftest resilient-bulk-load-handles-circuit-open-test
  (testing "Handles circuit breaker open gracefully and continues to next symbol"
    (let [mock-node (make-mock-node)
          symbols ["AAPL" "SPY"]
          start-date (LocalDate/of 2024 11 27)
          end-date (LocalDate/of 2024 11 27)
          call-count (atom 0)]

      (with-redefs [state/get-completed-dates (fn [_ _] #{})
                    ingest/plan-daily-work
                    (fn [symbol _ _ _]
                      [{:symbol symbol :date start-date :status :pending}])
                    bulk-load/process-daily-items!
                    (fn [_ symbol _ _]
                      (swap! call-count inc)
                      (if (= symbol "AAPL")
                        ;; First symbol - throw circuit open
                        (throw (ex-info "Circuit breaker open"
                                        {:type :circuit-open
                                         :symbol symbol
                                         :completed []
                                         :stats {:records 0}}))
                        ;; Second symbol - succeed
                        {:results []
                         :stats {:records 5 :fetched 1 :no-data 0 :errors 0}}))]

        (let [result (bulk-load/resilient-bulk-load!
                      mock-node symbols start-date end-date {:parallelism 1})]

          ;; Should have attempted both symbols
          (is (= 2 @call-count))

          ;; Should have 1 failed, 1 completed
          (is (= 1 (:symbols-failed (:stats result))))
          (is (= 1 (:symbols-completed (:stats result))))

          ;; Overall should not be success
          (is (not (:success result)))

          ;; First symbol should have error in results
          (is (contains? (get-in result [:results "AAPL"]) :error))
          (is (= "Circuit breaker open" (get-in result [:results "AAPL" :error]))))))))

(deftest resilient-bulk-load-handles-unexpected-error-test
  (testing "Handles unexpected errors and continues to next symbol"
    (let [mock-node (make-mock-node)
          symbols ["AAPL" "SPY"]
          start-date (LocalDate/of 2024 11 27)
          end-date (LocalDate/of 2024 11 27)]

      (with-redefs [state/get-completed-dates (fn [_ _] #{})
                    ingest/plan-daily-work
                    (fn [symbol _ _ _]
                      [{:symbol symbol :date start-date :status :pending}])
                    bulk-load/process-daily-items!
                    (fn [_ symbol _ _]
                      (if (= symbol "AAPL")
                        (throw (Exception. "Unexpected error"))
                        {:results []
                         :stats {:records 5 :fetched 1 :no-data 0 :errors 0}}))]

        (let [result (bulk-load/resilient-bulk-load!
                      mock-node symbols start-date end-date {:parallelism 1})]

          ;; Should have 1 failed, 1 completed
          (is (= 1 (:symbols-failed (:stats result))))
          (is (= 1 (:symbols-completed (:stats result))))
          (is (not (:success result)))

          ;; Error should be recorded
          (is (= "Unexpected error" (get-in result [:results "AAPL" :error]))))))))

(deftest resilient-bulk-load-aggregates-stats-test
  (testing "Aggregates stats across multiple symbols"
    (let [mock-node (make-mock-node)
          symbols ["AAPL" "SPY" "NVDA"]
          start-date (LocalDate/of 2024 11 27)
          end-date (LocalDate/of 2024 11 27)]

      (with-redefs [state/get-completed-dates (fn [_ _] #{})
                    ingest/plan-daily-work
                    (fn [symbol _ _ _]
                      [{:symbol symbol :date start-date :status :pending}])
                    bulk-load/process-daily-items!
                    (fn [_ _ _ _]
                      {:results []
                       :stats {:records 100 :fetched 1 :no-data 0 :errors 0}})]

        (let [result (bulk-load/resilient-bulk-load!
                      mock-node symbols start-date end-date {:parallelism 4})]

          ;; Total records should be sum of all symbols
          (is (= 300 (:total-records (:stats result))))
          (is (= 3 (:symbols-completed (:stats result))))
          (is (= 0 (:symbols-failed (:stats result))))
          (is (:success result)))))))

;;; ---------------------------------------------------------------------------
;;; bulk-load-from-repl! Tests
;;; ---------------------------------------------------------------------------

(deftest bulk-load-from-repl-validates-preconditions-test
  (testing "Validates node is present"
    (is (thrown? AssertionError
                 (bulk-load/bulk-load-from-repl! nil ["AAPL"]
                                                 (LocalDate/of 2024 11 27)
                                                 (LocalDate/of 2024 11 27)))))

  (testing "Validates symbols are present"
    (let [mock-node (make-mock-node)]
      (is (thrown? AssertionError
                   (bulk-load/bulk-load-from-repl! mock-node []
                                                   (LocalDate/of 2024 11 27)
                                                   (LocalDate/of 2024 11 27))))))

  (testing "Validates start-date is present"
    (let [mock-node (make-mock-node)]
      (is (thrown? AssertionError
                   (bulk-load/bulk-load-from-repl! mock-node ["AAPL"]
                                                   nil
                                                   (LocalDate/of 2024 11 27))))))

  (testing "Validates end-date is present"
    (let [mock-node (make-mock-node)]
      (is (thrown? AssertionError
                   (bulk-load/bulk-load-from-repl! mock-node ["AAPL"]
                                                   (LocalDate/of 2024 11 27)
                                                   nil))))))

(deftest bulk-load-from-repl-passes-through-to-resilient-test
  (testing "Delegates to resilient-bulk-load! with proper args"
    (let [mock-node (make-mock-node)
          symbols ["AAPL"]
          start-date (LocalDate/of 2024 11 25)
          end-date (LocalDate/of 2024 11 27)
          opts {:parallelism 8}
          delegated-args (atom nil)]

      (with-redefs [bulk-load/resilient-bulk-load!
                    (fn [node syms start end opts-map]
                      (reset! delegated-args {:node node
                                              :symbols syms
                                              :start start
                                              :end end
                                              :opts opts-map})
                      {:success true :results {} :stats {}})]

        (bulk-load/bulk-load-from-repl! mock-node symbols start-date end-date opts)

        ;; Verify args were passed through correctly
        (is (= mock-node (:node @delegated-args)))
        (is (= symbols (:symbols @delegated-args)))
        (is (= start-date (:start @delegated-args)))
        (is (= end-date (:end @delegated-args)))
        (is (= opts (:opts @delegated-args)))))))

(deftest bulk-load-from-repl-uses-default-opts-test
  (testing "Uses default empty opts when not provided"
    (let [mock-node (make-mock-node)
          symbols ["AAPL"]
          start-date (LocalDate/of 2024 11 27)
          end-date (LocalDate/of 2024 11 27)
          delegated-opts (atom nil)]

      (with-redefs [bulk-load/resilient-bulk-load!
                    (fn [_ _ _ _ opts-map]
                      (reset! delegated-opts opts-map)
                      {:success true :results {} :stats {}})]

        ;; Call without opts
        (bulk-load/bulk-load-from-repl! mock-node symbols start-date end-date)

        ;; Should receive empty opts map
        (is (= {} @delegated-opts))))))

;;; ---------------------------------------------------------------------------
;;; Helper Function Tests
;;; ---------------------------------------------------------------------------

(deftest parse-args-test
  (testing "Parses command-line arguments correctly"
    (let [args ["AAPL" "SPY" "--start" "2024-11-25" "--end" "2024-11-27" "--parallelism" "8"]
          result (#'bulk-load/parse-args args)]

      (is (= ["AAPL" "SPY"] (:symbols result)))
      (is (= (LocalDate/of 2024 11 25) (:start-date result)))
      (is (= (LocalDate/of 2024 11 27) (:end-date result)))
      (is (= 8 (:parallelism result)))))

  (testing "Handles optional db-path"
    (let [args ["AAPL" "--start" "2024-11-25" "--end" "2024-11-27" "--db-path" "custom/path"]
          result (#'bulk-load/parse-args args)]

      (is (= ["AAPL"] (:symbols result)))
      (is (= "custom/path" (:db-path result)))))

  (testing "Handles multiple symbols before flags"
    (let [args ["AAPL" "SPY" "NVDA" "GOOGL" "--start" "2024-11-25" "--end" "2024-11-27"]
          result (#'bulk-load/parse-args args)]

      (is (= ["AAPL" "SPY" "NVDA" "GOOGL"] (:symbols result))))))

(deftest local-date-to-eod-instant-test
  (testing "Converts LocalDate to 5pm ET Instant"
    (let [date (LocalDate/of 2024 11 27)
          eod (date-utils/local-date->eod-instant date)
          ny-zone (java.time.ZoneId/of "America/New_York")
          ny-time (.atZone eod ny-zone)]

      ;; Should be 5pm (17:00) in NY
      (is (= 17 (.getHour ny-time)))
      (is (= 0 (.getMinute ny-time)))
      (is (= 0 (.getSecond ny-time))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.data.bulk-load-test)

  ;; Run specific test
  (clojure.test/test-var #'import-status-structure-test)
  (clojure.test/test-var #'process-daily-items-successful-fetch-test)
  (clojure.test/test-var #'resilient-bulk-load-skips-completed-dates-test)
  (clojure.test/test-var #'bulk-load-from-repl-validates-preconditions-test))
