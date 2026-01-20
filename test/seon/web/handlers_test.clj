(ns seon.web.handlers-test
  "Unit tests for HTTP request handlers.

  Tests cover:
  - Health check endpoint
  - Dashboard HTML endpoint
  - Log viewer endpoints"
  (:require [clojure.test :refer [deftest testing is]]
            [jsonista.core :as json]
            [seon.web.handlers :as handlers]
            [seon.web.logs :as logs])
  (:import [java.io ByteArrayInputStream]))

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

(defn parse-json-response
  "Parse JSON from a response body string."
  [response]
  (json/read-value (:body response) json/keyword-keys-object-mapper))

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

  (testing "HTML includes SSE connection initialization"
    (let [response (handlers/dashboard (mock-request))
          body (:body response)]
      (is (clojure.string/includes? body "data-init")))))

;;; ---------------------------------------------------------------------------
;;; Log Viewer Endpoint Tests
;;; ---------------------------------------------------------------------------

(deftest log-viewer-handler-test
  (testing "Returns 200 with HTML content type"
    (let [response (handlers/log-viewer (mock-request))]
      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))))

  (testing "Response body contains log viewer HTML"
    (let [response (handlers/log-viewer (mock-request))
          body (:body response)]
      (is (string? body))
      (is (clojure.string/includes? body "Log Viewer")))))

;;; ---------------------------------------------------------------------------
;;; Integration-Style Tests (Multiple Handler Interactions)
;;; ---------------------------------------------------------------------------

(deftest handler-integration-test
  (testing "Health check works before and after dashboard access"
    (let [health1 (handlers/health (mock-request))
          _dashboard (handlers/dashboard (mock-request))
          health2 (handlers/health (mock-request))]
      (is (= 200 (:status health1)))
      (is (= 200 (:status health2))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.web.handlers-test)

  ;; Run specific test
  (clojure.test/test-var #'health-handler-test)
  (clojure.test/test-var #'dashboard-handler-test)
  (clojure.test/test-var #'log-viewer-handler-test)
  (clojure.test/test-var #'handler-integration-test)

  ;; Test individual handlers manually
  (handlers/health (mock-request))
  (handlers/dashboard (mock-request)))
