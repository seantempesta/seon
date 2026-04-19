(ns seon.web.handlers-test
  "Unit tests for HTTP request handlers.

  Tests cover:
  - Health check endpoint
  - Dashboard HTML endpoint
  - Log viewer endpoints"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jsonista.core :as json]
            [seon.web.handlers :as handlers]))

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
  (testing "Returns JSON content type"
    (let [response (handlers/health-check (mock-request))]
      (is (#{200 503} (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))))

  (testing "Response contains required fields"
    (let [response (handlers/health-check (mock-request))
          body (parse-json-response response)]
      (is (string? (:status body)))
      (is (string? (:timestamp body))))))

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
      (is (str/includes? body "<!DOCTYPE html"))
      (is (str/includes? body "<html"))))

  (testing "HTML includes Datastar script"
    (let [response (handlers/dashboard (mock-request))
          body (:body response)]
      (is (str/includes? body "datastar"))
      ;; Using local copy now, not CDN
      (is (str/includes? body "/js/datastar.js"))))

  (testing "HTML includes SSE connection initialization"
    (let [response (handlers/dashboard (mock-request))
          body (:body response)]
      (is (str/includes? body "data-init")))))

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
      (is (str/includes? body "Log Viewer")))))

;;; ---------------------------------------------------------------------------
;;; Integration-Style Tests (Multiple Handler Interactions)
;;; ---------------------------------------------------------------------------

(deftest handler-integration-test
  (testing "Health check works before and after dashboard access"
    (let [health1 (handlers/health-check (mock-request))
          _dashboard (handlers/dashboard (mock-request))
          health2 (handlers/health-check (mock-request))]
      (is (#{200 503} (:status health1)))
      (is (#{200 503} (:status health2))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.web.handlers-test)

  ;; Run specific test
  (clojure.test/test-var #'health-handler-test)
  (clojure.test/test-var #'dashboard-handler-test)
  (clojure.test/test-var #'log-viewer-handler-test)
  (clojure.test/test-var #'handler-integration-test)

  ;; Test individual handlers manually
  (handlers/health-check (mock-request))
  (handlers/dashboard (mock-request)))
