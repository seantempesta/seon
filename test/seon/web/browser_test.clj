(ns seon.web.browser-test
  "Unit tests for browser REPL-to-browser execution bridge.

  Tests cover:
  - escape-js-string - escaping backslashes, quotes, newlines, tabs
  - format-eval-event - SSE event structure, code inclusion
  - deliver-result! - delivering to pending promise, unknown exec-id
  - eval! no clients - throws when no clients connected
  - result-handler - 200/404/400 responses
  - EvalResult schema"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [jsonista.core :as json]
            [malli.core :as m]
            [seon.web.browser :as browser]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:private pending-evals-atom @#'browser/pending-evals)

(defn- cleanup-pending-evals
  "Fixture to clean up pending-evals after each test."
  [f]
  (try
    (f)
    (finally
      (reset! pending-evals-atom {}))))

(use-fixtures :each cleanup-pending-evals)

;;; ---------------------------------------------------------------------------
;;; Test Helpers
;;; ---------------------------------------------------------------------------

(defn- mock-request
  "Create a mock Ring request map."
  ([]
   (mock-request {}))
  ([overrides]
   (merge {:request-method :post
           :uri "/api/browser/result"
           :headers {"content-type" "application/json"}
           :body nil}
          overrides)))

(defn parse-json-response
  "Parse JSON from a response body string."
  [response]
  (json/read-value (:body response) json/keyword-keys-object-mapper))

;;; ---------------------------------------------------------------------------
;;; escape-js-string Tests
;;; ---------------------------------------------------------------------------

(deftest escape-js-string-test
  (let [escape-js-string @#'browser/escape-js-string]

    (testing "Escapes backslashes"
      (is (= "\\\\" (escape-js-string "\\")))
      (is (= "\\\\\\\\" (escape-js-string "\\\\")))
      (is (= "a\\\\b\\\\c" (escape-js-string "a\\b\\c"))))

    (testing "Escapes double quotes"
      (is (= "\\\"" (escape-js-string "\"")))
      (is (= "\\\"hello\\\"" (escape-js-string "\"hello\"")))
      (is (= "say \\\"hi\\\"" (escape-js-string "say \"hi\""))))

    (testing "Escapes newlines"
      (is (= "\\n" (escape-js-string "\n")))
      (is (= "line1\\nline2" (escape-js-string "line1\nline2")))
      (is (= "a\\nb\\nc" (escape-js-string "a\nb\nc"))))

    (testing "Escapes carriage returns"
      (is (= "\\r" (escape-js-string "\r")))
      (is (= "line1\\r\\nline2" (escape-js-string "line1\r\nline2"))))

    (testing "Escapes tabs"
      (is (= "\\t" (escape-js-string "\t")))
      (is (= "col1\\tcol2" (escape-js-string "col1\tcol2"))))

    (testing "Handles combined special characters"
      (is (= "\\\"\\\\\\n\\t" (escape-js-string "\"\\\n\t")))
      (is (= "path\\\\to\\\\file\\nwith \\\"quotes\\\""
             (escape-js-string "path\\to\\file\nwith \"quotes\""))))

    (testing "Preserves normal characters"
      (is (= "" (escape-js-string "")))
      (is (= "hello world" (escape-js-string "hello world")))
      (is (= "document.title" (escape-js-string "document.title")))
      (is (= "123 abc !@#$%^&*()" (escape-js-string "123 abc !@#$%^&*()"))))))

;;; ---------------------------------------------------------------------------
;;; format-eval-event Tests
;;; ---------------------------------------------------------------------------

(deftest format-eval-event-test
  (let [format-eval-event @#'browser/format-eval-event]

    (testing "Returns SSE event with correct structure"
      (let [event (format-eval-event "test-123" "document.title")]
        ;; SSE event starts with event type
        (is (str/starts-with? event "event: datastar-patch-elements\n"))
        ;; Has data lines
        (is (str/includes? event "data: selector body"))
        (is (str/includes? event "data: mode append"))
        (is (str/includes? event "data: elements"))
        ;; Ends with double newline (SSE message terminator)
        (is (str/ends-with? event "\n\n"))))

    (testing "Includes exec-id in event"
      (let [exec-id "unique-exec-abc"
            event (format-eval-event exec-id "1+1")]
        ;; The exec-id appears in the hidden div id
        (is (str/includes? event (str "id=\"seon-eval-" exec-id "\"")))
        ;; And in the execId variable assignment
        (is (str/includes? event (str "var execId=\"" exec-id "\"")))))

    (testing "Includes escaped JavaScript code"
      (let [event (format-eval-event "test" "console.log(\"hello\")")]
        ;; The code should be escaped (quotes become \")
        (is (str/includes? event "console.log(\\\"hello\\\")"))))

    (testing "Includes fetch call to /api/browser/result"
      (let [event (format-eval-event "test" "1")]
        (is (str/includes? event "fetch('/api/browser/result'"))
        (is (str/includes? event "method:'POST'"))))

    (testing "Script removes itself from DOM"
      (let [event (format-eval-event "test-xyz" "1")]
        (is (str/includes? event "getElementById('seon-eval-'+execId)"))
        (is (str/includes? event "if(el)el.remove();"))))

    (testing "Handles complex code with special characters"
      (let [code "document.querySelector(\"#span-count\").textContent"
            event (format-eval-event "test" code)]
        ;; Should be properly escaped
        (is (str/includes? event "document.querySelector(\\\"#span-count\\\").textContent"))))))

;;; ---------------------------------------------------------------------------
;;; deliver-result! Tests
;;; ---------------------------------------------------------------------------

(deftest deliver-result-success-test
  (testing "Delivers result to pending promise"
    (let [exec-id "deliver-test-1"
          p (promise)]
      ;; Set up pending eval
      (swap! pending-evals-atom assoc exec-id
             {:promise p
              :started-at (System/currentTimeMillis)
              :ns-sym 'test.ns})
      ;; Deliver result
      (let [result (browser/deliver-result! exec-id {:result "42"})]
        (is (true? result))
        ;; Promise should be realized with the result
        (is (realized? p))
        (is (= {:result "42"} @p))))))

(deftest deliver-result-error-test
  (testing "Delivers error to pending promise"
    (let [exec-id "deliver-test-2"
          p (promise)]
      (swap! pending-evals-atom assoc exec-id
             {:promise p
              :started-at (System/currentTimeMillis)
              :ns-sym 'test.ns})
      (let [result (browser/deliver-result! exec-id {:error "ReferenceError: x is not defined"})]
        (is (true? result))
        (is (realized? p))
        (is (= {:error "ReferenceError: x is not defined"} @p))))))

(deftest deliver-result-unknown-exec-id-test
  (testing "Returns false for unknown exec-id"
    (let [result (browser/deliver-result! "nonexistent-id" {:result "value"})]
      (is (false? result))))

  (testing "Returns false when pending-evals is empty"
    (reset! pending-evals-atom {})
    (let [result (browser/deliver-result! "any-id" {:result "value"})]
      (is (false? result)))))

(deftest deliver-result-does-not-remove-from-pending-test
  (testing "deliver-result! does not remove the entry (eval! handles cleanup)"
    (let [exec-id "deliver-cleanup-test"
          p (promise)]
      (swap! pending-evals-atom assoc exec-id
             {:promise p
              :started-at (System/currentTimeMillis)
              :ns-sym 'test.ns})
      (browser/deliver-result! exec-id {:result "done"})
      ;; Entry should still be in pending-evals (eval! removes it)
      (is (contains? @pending-evals-atom exec-id)))))

;;; ---------------------------------------------------------------------------
;;; eval! No Clients Tests
;;; ---------------------------------------------------------------------------

(deftest eval-no-clients-test
  (testing "Throws when no clients connected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No connected browser clients"
         (browser/eval! 'seon.nonexistent.namespace "1+1"))))

  (testing "Exception includes namespace and hint"
    (try
      (browser/eval! 'seon.nonexistent.namespace "document.title")
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= 'seon.nonexistent.namespace (:ns data)))
          (is (some? (:hint data)))
          (is (str/includes? (:hint data) "browser")))))))

;;; ---------------------------------------------------------------------------
;;; result-handler Tests
;;; ---------------------------------------------------------------------------

(deftest result-handler-success-test
  (testing "Returns 200 when pending eval exists"
    (let [exec-id "handler-test-1"
          p (promise)]
      ;; Set up pending eval
      (swap! pending-evals-atom assoc exec-id
             {:promise p
              :started-at (System/currentTimeMillis)
              :ns-sym 'test.ns})
      ;; Call handler
      (let [response (browser/result-handler
                      (mock-request {:body {:id exec-id :result "success"}}))]
        (is (= 200 (:status response)))
        (is (= "application/json" (get-in response [:headers "Content-Type"])))
        (let [body (parse-json-response response)]
          (is (true? (:success body))))
        ;; Promise should have the result (with additional browser metadata)
        (is (= "success" (:result @p)))))))

(deftest result-handler-error-result-test
  (testing "Returns 200 when delivering error result"
    (let [exec-id "handler-test-2"
          p (promise)]
      (swap! pending-evals-atom assoc exec-id
             {:promise p
              :started-at (System/currentTimeMillis)
              :ns-sym 'test.ns})
      (let [response (browser/result-handler
                      (mock-request {:body {:id exec-id :error "TypeError"}}))]
        (is (= 200 (:status response)))
        ;; Promise should have the error (with additional browser metadata)
        (is (= "TypeError" (:error @p)))))))

(deftest result-handler-not-found-test
  (testing "Returns 404 when no pending eval for id"
    (let [response (browser/result-handler
                    (mock-request {:body {:id "unknown-exec-id" :result "value"}}))]
      (is (= 404 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (let [body (parse-json-response response)]
        (is (false? (:success body)))
        (is (str/includes? (:error body) "pending"))))))

(deftest result-handler-missing-id-test
  (testing "Returns 400 when id is missing"
    (let [response (browser/result-handler
                    (mock-request {:body {:result "value"}}))]
      (is (= 400 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (let [body (parse-json-response response)]
        (is (false? (:success body)))
        (is (str/includes? (:error body) "id")))))

  (testing "Returns 400 when body is nil"
    (let [response (browser/result-handler (mock-request {:body nil}))]
      (is (= 400 (:status response)))))

  (testing "Returns 400 when body is empty map"
    (let [response (browser/result-handler (mock-request {:body {}}))]
      (is (= 400 (:status response))))))

;;; ---------------------------------------------------------------------------
;;; connected? and clients Tests
;;; ---------------------------------------------------------------------------

(deftest connected-test
  (testing "Returns false when no clients for namespace"
    (is (false? (browser/connected? 'seon.nonexistent.namespace)))))

(deftest clients-test
  (testing "Returns nil/empty when no clients for namespace"
    (let [result (browser/clients 'seon.nonexistent.namespace)]
      (is (or (nil? result) (empty? result))))))

;;; ---------------------------------------------------------------------------
;;; errors Function Tests
;;; ---------------------------------------------------------------------------

(deftest errors-no-clients-test
  (testing "Throws when no clients connected (needs browser)"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No connected browser clients"
         (browser/errors 'seon.nonexistent.namespace))))

  (testing "Exception includes namespace and hint"
    (try
      (browser/errors 'seon.test.ns)
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= 'seon.test.ns (:ns data)))
          (is (some? (:hint data))))))))

;;; ---------------------------------------------------------------------------
;;; eval!! and cljs!! Tests
;;; ---------------------------------------------------------------------------

(deftest eval!!-no-clients-test
  (testing "Throws when no clients connected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No connected browser clients"
         (browser/eval!! 'seon.nonexistent.namespace "[1, 2, 3]")))))

(deftest cljs!!-no-clients-test
  (testing "Throws when no clients connected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No connected browser clients"
         (browser/cljs!! 'seon.nonexistent.namespace '(+ 1 2 3))))))

;;; ---------------------------------------------------------------------------
;;; clear-errors! Tests
;;; ---------------------------------------------------------------------------

(deftest clear-errors-no-clients-test
  (testing "Throws when no clients connected (needs browser)"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No connected browser clients"
         (browser/clear-errors! 'seon.nonexistent.namespace)))))

;;; ---------------------------------------------------------------------------
;;; EvalResult Schema Tests
;;; ---------------------------------------------------------------------------

;; Define an EvalResult schema for test validation
;; This matches what browser/eval! returns: a string result

(def EvalResult
  "Schema for browser eval result - a string representation of the JS result."
  [:string {:min 0}])

(deftest eval-result-schema-test
  (testing "EvalResult schema exists and is valid"
    (is (m/schema? (m/schema EvalResult))))

  (testing "EvalResult validates string results"
    (is (m/validate EvalResult "42"))
    (is (m/validate EvalResult "undefined"))
    (is (m/validate EvalResult "null"))
    (is (m/validate EvalResult "{\"key\":\"value\"}"))
    (is (m/validate EvalResult "")))

  (testing "EvalResult rejects non-strings"
    (is (not (m/validate EvalResult 42)))
    (is (not (m/validate EvalResult nil)))
    (is (not (m/validate EvalResult {:result "value"})))
    (is (not (m/validate EvalResult ["array"])))))

;;; ---------------------------------------------------------------------------
;;; cleanup-stale-evals! Tests
;;; ---------------------------------------------------------------------------

(deftest cleanup-stale-evals-test
  (let [cleanup-stale-evals! @#'browser/cleanup-stale-evals!]

    (testing "Removes entries older than 60 seconds"
      (let [old-time (- (System/currentTimeMillis) 70000) ; 70 seconds ago
            new-time (System/currentTimeMillis)]
        (reset! pending-evals-atom
                {"old-eval" {:promise (promise)
                             :started-at old-time
                             :ns-sym 'test}
                 "new-eval" {:promise (promise)
                             :started-at new-time
                             :ns-sym 'test}})
        (cleanup-stale-evals!)
        (is (not (contains? @pending-evals-atom "old-eval")))
        (is (contains? @pending-evals-atom "new-eval"))))

    (testing "Keeps entries less than 60 seconds old"
      (let [recent-time (- (System/currentTimeMillis) 30000)] ; 30 seconds ago
        (reset! pending-evals-atom
                {"recent-eval" {:promise (promise)
                                :started-at recent-time
                                :ns-sym 'test}})
        (cleanup-stale-evals!)
        (is (contains? @pending-evals-atom "recent-eval"))))

    (testing "Handles empty pending-evals"
      (reset! pending-evals-atom {})
      (cleanup-stale-evals!)
      (is (= {} @pending-evals-atom)))))

;;; ---------------------------------------------------------------------------
;;; cljs! Tests (unit test - no browser needed)
;;; ---------------------------------------------------------------------------

(deftest cljs-no-clients-test
  (testing "Throws when no clients connected (same as eval!)"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No connected browser clients"
         (browser/cljs! 'seon.nonexistent.namespace '(+ 1 2 3))))))

;;; ---------------------------------------------------------------------------
;;; Development Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.web.browser-test)

  ;; Run specific test
  (clojure.test/test-var #'escape-js-string-test)
  (clojure.test/test-var #'format-eval-event-test)
  (clojure.test/test-var #'deliver-result-success-test)
  (clojure.test/test-var #'result-handler-success-test)
  (clojure.test/test-var #'eval-no-clients-test)
  (clojure.test/test-var #'cleanup-stale-evals-test)

  ;; Quick manual tests
  (let [escape-js-string @#'browser/escape-js-string]
    (escape-js-string "hello \"world\"\nwith\ttabs"))

  (let [format-eval-event @#'browser/format-eval-event]
    (println (format-eval-event "test-123" "document.title")))

  nil)
