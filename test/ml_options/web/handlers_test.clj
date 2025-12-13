(ns ml-options.web.handlers-test
  "Unit tests for HTTP request handlers.

  Tests cover:
  - Health check endpoint
  - Dashboard HTML endpoint
  - Job status endpoint
  - Start import endpoint with validation
  - Stop import endpoint"
  (:require [clojure.test :refer [deftest testing is]]
            [jsonista.core :as json]
            [ml-options.web.handlers :as handlers]
            [ml-options.web.jobs :as jobs]
            [ml-options.web.sse :as sse]
            [ml-options.web.html :as html])
  (:import [java.io ByteArrayInputStream]
           [java.time LocalDate]))

;;; ---------------------------------------------------------------------------
;;; Test Helpers
;;; ---------------------------------------------------------------------------

(defn mock-request
  "Create a mock Ring request map."
  ([]
   (mock-request {}))
  ([overrides]
   (merge {:request-method :get
           :uri "/"
           :headers {}
           :body nil}
          overrides)))

(defn json-body
  "Create a mock request body from a Clojure map."
  [data]
  (ByteArrayInputStream. (.getBytes (json/write-value-as-string data))))

(defn json-request
  "Create a mock request with JSON body and proper content-type header."
  [data]
  (mock-request
   {:headers {"content-type" "application/json"}
    :body (json-body data)}))

(defn parse-json-response
  "Parse JSON from a response body string."
  [response]
  (json/read-value (:body response) json/keyword-keys-object-mapper))

(defn reset-job-state!
  "Reset job state before each test."
  []
  (reset! jobs/job-state {:current nil :history []}))

;;; ---------------------------------------------------------------------------
;;; Health Endpoint Tests
;;; ---------------------------------------------------------------------------

(deftest health-handler-test
  (testing "Returns 200 with JSON content type"
    (let [response (handlers/health (mock-request))]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))))

  (testing "Response contains required fields"
    (let [response (handlers/health (mock-request))
          body (parse-json-response response)]
      (is (= "ok" (:status body)))
      (is (string? (:timestamp body)))))

  (testing "Timestamp is valid ISO-8601 instant"
    (let [response (handlers/health (mock-request))
          body (parse-json-response response)
          timestamp (:timestamp body)]
      ;; Should parse without error
      (is (some? (java.time.Instant/parse timestamp))))))

;;; ---------------------------------------------------------------------------
;;; Dashboard Endpoint Tests
;;; ---------------------------------------------------------------------------

(deftest dashboard-handler-test
  (testing "Returns 200 with HTML content type"
    (let [response (handlers/dashboard (mock-request))]
      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))))

  (testing "Response body is non-empty HTML string"
    (let [response (handlers/dashboard (mock-request))
          body (:body response)]
      (is (string? body))
      (is (pos? (count body)))
      (is (clojure.string/includes? body "<!DOCTYPE html"))
      (is (clojure.string/includes? body "<html"))))

  (testing "HTML includes Datastar script"
    (let [response (handlers/dashboard (mock-request))
          body (:body response)]
      (is (clojure.string/includes? body "datastar"))
      (is (clojure.string/includes? body "cdn.jsdelivr.net"))))

  (testing "HTML includes dashboard title"
    (let [response (handlers/dashboard (mock-request))
          body (:body response)]
      (is (clojure.string/includes? body "ML Options Trading")
          (is (clojure.string/includes? body "Import Dashboard")))))

  (testing "HTML includes SSE connection initialization"
    (let [response (handlers/dashboard (mock-request))
          body (:body response)]
      (is (clojure.string/includes? body "data-init")))))

;;; ---------------------------------------------------------------------------
;;; Job Status Endpoint Tests
;;; ---------------------------------------------------------------------------

(deftest job-status-handler-test
  (testing "Returns 200 with JSON content type"
    (reset-job-state!)
    (let [response (handlers/job-status (mock-request))]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))))

  (testing "Returns status with no current job"
    (reset-job-state!)
    (let [response (handlers/job-status (mock-request))
          body (parse-json-response response)]
      (is (nil? (:current body)))
      (is (zero? (:history-count body)))))

  (testing "Returns current job status when job is running"
    (reset-job-state!)
    (reset! jobs/job-state
            {:current {:id "test-job-123"
                       :status :running
                       :symbols ["AAPL" "SPY"]
                       :start-date "2024-01-01"
                       :end-date "2024-12-31"
                       :progress {:days-completed 10
                                  :total-days 100
                                  :records-loaded 5000}}
             :history []})
    (let [response (handlers/job-status (mock-request))
          body (parse-json-response response)
          current (:current body)]
      (is (= "test-job-123" (:id current)))
      (is (= "running" (name (:status current))))
      (is (= ["AAPL" "SPY"] (:symbols current)))
      (is (= "2024-01-01" (:start-date current)))
      (is (= "2024-12-31" (:end-date current)))
      (is (= 10 (get-in current [:progress :days-completed])))
      (is (= 100 (get-in current [:progress :total-days])))
      (is (= 5000 (get-in current [:progress :records-loaded])))))

  (testing "Returns job history count"
    (reset-job-state!)
    (reset! jobs/job-state
            {:current nil
             :history [{:id "job-1" :status :completed}
                       {:id "job-2" :status :completed}
                       {:id "job-3" :status :failed}]})
    (let [response (handlers/job-status (mock-request))
          body (parse-json-response response)]
      (is (= 3 (:history-count body)))))

  (testing "Does not include :future key in response"
    (reset-job-state!)
    (reset! jobs/job-state
            {:current {:id "test-job"
                       :status :running
                       :future (future (Thread/sleep 1000))}
             :history []})
    (let [response (handlers/job-status (mock-request))
          body (parse-json-response response)]
      (is (not (contains? (:current body) :future))))))

;;; ---------------------------------------------------------------------------
;;; Start Import Endpoint Tests
;;; ---------------------------------------------------------------------------

(deftest start-import-handler-test
  (testing "Returns 200 when starting a job successfully"
    (reset-job-state!)
    (let [request (json-request {:symbols "AAPL,SPY"
                                 :startDate "2024-01-01"
                                 :endDate "2024-12-31"})
          response (with-redefs [jobs/get-node (fn [] :mock-node)
                                 jobs/start-import! (fn [node symbols start end opts]
                                                      {:ok "job-123"})]
                     (handlers/start-import request))
          body (parse-json-response response)]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (is (= "job-123" (:ok body)))))

  (testing "Parses comma-separated symbols correctly"
    (reset-job-state!)
    (let [request (json-request {:symbols "AAPL, SPY, NVDA , GOOGL"
                                 :startDate "2024-01-01"
                                 :endDate "2024-12-31"})
          captured-symbols (atom nil)]
      (with-redefs [jobs/get-node (fn [] :mock-node)
                    jobs/start-import! (fn [node symbols start end opts]
                                         (reset! captured-symbols symbols)
                                         {:ok "job-123"})]
        (handlers/start-import request)
        (is (= ["AAPL" "SPY" "NVDA" "GOOGL"] @captured-symbols)))))

  (testing "Parses date strings to LocalDate"
    (reset-job-state!)
    (let [request (json-request {:symbols "AAPL"
                                 :startDate "2024-01-15"
                                 :endDate "2024-12-31"})
          captured-dates (atom {})]
      (with-redefs [jobs/get-node (fn [] :mock-node)
                    jobs/start-import! (fn [node symbols start end opts]
                                         (reset! captured-dates {:start start :end end})
                                         {:ok "job-123"})]
        (handlers/start-import request)
        (is (instance? LocalDate (:start @captured-dates)))
        (is (instance? LocalDate (:end @captured-dates)))
        (is (= "2024-01-15" (str (:start @captured-dates))))
        (is (= "2024-12-31" (str (:end @captured-dates)))))))

  (testing "Passes parallelism option"
    (reset-job-state!)
    (let [request (json-request {:symbols "AAPL"
                                 :startDate "2024-01-01"
                                 :endDate "2024-12-31"})
          captured-opts (atom nil)]
      (with-redefs [jobs/get-node (fn [] :mock-node)
                    jobs/start-import! (fn [node symbols start end opts]
                                         (reset! captured-opts opts)
                                         {:ok "job-123"})]
        (handlers/start-import request)
        (is (= 4 (:parallelism @captured-opts))))))

  (testing "Starts import job successfully"
    (reset-job-state!)
    (let [request (json-request {:symbols "AAPL"
                                 :startDate "2024-01-01"
                                 :endDate "2024-12-31"})]
      (with-redefs [jobs/get-node (fn [] :mock-node)
                    jobs/start-import! (fn [node symbols start end opts]
                                         {:ok "job-123"})]
        (let [response (handlers/start-import request)
              body (parse-json-response response)]
          (is (= 200 (:status response)))
          (is (= "job-123" (:ok body)))))))

  (testing "Returns 400 for invalid date format"
    (reset-job-state!)
    (let [request (json-request {:symbols "AAPL"
                                 :startDate "invalid-date"
                                 :endDate "2024-12-31"})
          response (handlers/start-import request)
          body (parse-json-response response)]
      (is (= 400 (:status response)))
      (is (string? (:error body)))
      (is (clojure.string/includes? (:error body) "could not be parsed"))))

  (testing "Returns 400 for missing required fields"
    (reset-job-state!)
    (let [request (json-request {:symbols "AAPL"
                                 ;; Missing startDate and endDate
                                 })
          response (handlers/start-import request)]
      (is (= 400 (:status response)))))

  (testing "Returns 400 for malformed JSON"
    (reset-job-state!)
    (let [request (mock-request
                   {:body (ByteArrayInputStream. (.getBytes "not valid json"))})
          response (handlers/start-import request)]
      (is (= 400 (:status response)))))

  (testing "Returns error when job already running"
    (reset-job-state!)
    (let [request (json-request {:symbols "AAPL"
                                 :startDate "2024-01-01"
                                 :endDate "2024-12-31"})
          response (with-redefs [jobs/get-node (fn [] :mock-node)
                                 jobs/start-import! (fn [node symbols start end opts]
                                                      {:error "A job is already running"})]
                     (handlers/start-import request))
          body (parse-json-response response)]
      (is (= 200 (:status response)))  ;; Still 200, error in body
      (is (= "A job is already running" (:error body)))))

  (testing "Handles single symbol without comma"
    (reset-job-state!)
    (let [request (json-request {:symbols "AAPL"
                                 :startDate "2024-01-01"
                                 :endDate "2024-12-31"})
          captured-symbols (atom nil)]
      (with-redefs [jobs/get-node (fn [] :mock-node)
                    jobs/start-import! (fn [node symbols start end opts]
                                         (reset! captured-symbols symbols)
                                         {:ok "job-123"})]
        (handlers/start-import request)
        (is (= ["AAPL"] @captured-symbols))))))

;;; ---------------------------------------------------------------------------
;;; Stop Import Endpoint Tests
;;; ---------------------------------------------------------------------------

(deftest stop-import-handler-test
  (testing "Returns 200 when stopping job successfully"
    (reset-job-state!)
    (let [response (with-redefs [jobs/stop-job! (fn []
                                                  {:ok "Job stop requested"})]
                     (handlers/stop-import (mock-request)))
          body (parse-json-response response)]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (is (= "Job stop requested" (:ok body)))))

  (testing "Stop job succeeds"
    (reset-job-state!)
    (with-redefs [jobs/stop-job! (fn []
                                   {:ok "Job stop requested"})]
      (let [response (handlers/stop-import (mock-request))
            body (parse-json-response response)]
        (is (= 200 (:status response)))
        (is (= "Job stop requested" (:ok body))))))

  (testing "Returns error when no job is running"
    (reset-job-state!)
    (let [response (with-redefs [jobs/stop-job! (fn []
                                                  {:error "No current job"})]
                     (handlers/stop-import (mock-request)))
          body (parse-json-response response)]
      (is (= 200 (:status response)))  ;; Still 200, error in body
      (is (= "No current job" (:error body)))))

  (testing "Returns error when job is not in running state"
    (reset-job-state!)
    (let [response (with-redefs [jobs/stop-job! (fn []
                                                  {:error "Job is not running, status: completed"})]
                     (handlers/stop-import (mock-request)))
          body (parse-json-response response)]
      (is (= 200 (:status response)))
      (is (clojure.string/includes? (:error body) "not running"))))

  (testing "Returns 500 when exception occurs"
    (reset-job-state!)
    (let [response (with-redefs [jobs/stop-job! (fn []
                                                  (throw (Exception. "Internal error")))]
                     (handlers/stop-import (mock-request)))
          body (parse-json-response response)]
      (is (= 500 (:status response)))
      (is (= "Internal error" (:error body)))))

  (testing "Handles exception during stop job"
    (reset-job-state!)
    (let [response (with-redefs [jobs/stop-job! (fn []
                                                  (throw (Exception. "Stop error")))]
                     (handlers/stop-import (mock-request)))]
      (is (= 500 (:status response))))))

;;; ---------------------------------------------------------------------------
;;; Integration-Style Tests (Multiple Handler Interactions)
;;; ---------------------------------------------------------------------------

(deftest handler-integration-test
  (testing "Health check works before and after dashboard access"
    (let [health1 (handlers/health (mock-request))
          _dashboard (handlers/dashboard (mock-request))
          health2 (handlers/health (mock-request))]
      (is (= 200 (:status health1)))
      (is (= 200 (:status health2)))))

  (testing "Job lifecycle: status -> start -> status -> stop -> status"
    (reset-job-state!)
    (let [;; Check initial status
          status1 (handlers/job-status (mock-request))
          body1 (parse-json-response status1)

          ;; Start a job
          start-resp (with-redefs [jobs/get-node (fn [] :mock-node)
                                   jobs/start-import! (fn [node symbols start end opts]
                                                        (swap! jobs/job-state assoc :current
                                                               {:id "job-123"
                                                                :status :running
                                                                :symbols symbols})
                                                        {:ok "job-123"})]
                       (handlers/start-import
                        (json-request {:symbols "AAPL"
                                       :startDate "2024-01-01"
                                       :endDate "2024-12-31"})))
          start-body (parse-json-response start-resp)

          ;; Check status again
          status2 (handlers/job-status (mock-request))
          body2 (parse-json-response status2)

          ;; Stop the job
          stop-resp (with-redefs [jobs/stop-job! (fn []
                                                   (swap! jobs/job-state assoc-in [:current :status] :stopping)
                                                   {:ok "Job stopped"})]
                      (handlers/stop-import (mock-request)))
          stop-body (parse-json-response stop-resp)

          ;; Check final status
          status3 (handlers/job-status (mock-request))
          body3 (parse-json-response status3)]

      ;; Verify lifecycle
      (is (nil? (:current body1)) "No job initially")
      (is (= "job-123" (:ok start-body)) "Job started")
      (is (= "job-123" (get-in body2 [:current :id])) "Job visible in status")
      (is (= "Job stopped" (:ok stop-body)) "Job stopped")
      (is (= "stopping" (name (get-in body3 [:current :status]))) "Job status updated"))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'ml-options.web.handlers-test)

  ;; Run specific test
  (clojure.test/test-var #'health-handler-test)
  (clojure.test/test-var #'dashboard-handler-test)
  (clojure.test/test-var #'job-status-handler-test)
  (clojure.test/test-var #'start-import-handler-test)
  (clojure.test/test-var #'stop-import-handler-test)
  (clojure.test/test-var #'handler-integration-test)

  ;; Test individual handlers manually
  (handlers/health (mock-request))
  (handlers/dashboard (mock-request))
  (handlers/job-status (mock-request))

  ;; Test with mock data
  (reset-job-state!)
  (handlers/start-import
   (mock-request
    {:body (json-body {:symbols "AAPL,SPY"
                       :startDate "2024-01-01"
                       :endDate "2024-12-31"})})))
