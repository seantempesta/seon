(ns seon.ai.agent-test
  "Tests for seon.ai.agent provider multimethods.

   Tests cover:
   1. Schema registration - agent schemas are in global registry
   2. normalize-message multimethod - Claude implementation
   3. result-message? multimethod - Claude implementation
   4. parse-result multimethod - Claude implementation
   5. Default implementations throw helpful errors"
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [malli.generator :as mg]
   [seon.ai :as ai]
   [seon.ai.agent :as agent]
   [seon.ai.claude :as claude]
   [seon.schema :as schema])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration Tests
;;; ---------------------------------------------------------------------------

(deftest schema-registration-test
  (testing "Agent schemas are registered in global registry"
    (let [agent-schemas (schema/schemas-in-namespace "seon.ai.agent")]
      (is (pos? (count agent-schemas)) "Should have registered schemas")
      (is (contains? agent-schemas ::agent/parsed-result)))))

(deftest schema-validity-test
  (testing "All registered agent schemas are valid Malli schemas"
    (doseq [[k _] (schema/schemas-in-namespace "seon.ai.agent")]
      (is (m/schema? (m/schema k))
          (str "Schema " k " should be a valid Malli schema")))))

(deftest parsed-result-generation-test
  (testing "Can generate valid ::parsed-result samples"
    (let [samples (mg/sample ::agent/parsed-result {:size 10})]
      (is (= 10 (count samples)))
      (doseq [result samples]
        (is (m/validate ::agent/parsed-result result))
        (is (#{:completed :failed :interrupted :error} (::agent/status result)))))))

;;; ---------------------------------------------------------------------------
;;; normalize-message Tests (Claude Provider)
;;; ---------------------------------------------------------------------------

(deftest normalize-message-assistant-test
  (testing "Normalizes Claude assistant message"
    (let [sdk-msg {:type "assistant"
                   :uuid "msg-123"
                   :message {:role "assistant"
                             :content [{:type "text" :text "Hello, world!"}]}}
          entity (agent/normalize-message {:provider :claude
                                           :message sdk-msg})]
      (is (string? (:xt/id entity)))
      (is (clojure.string/starts-with? (:xt/id entity) "msg-"))
      (is (= :message (::ai/type entity)))
      (is (= "assistant" (::ai/role entity)))
      (is (= "Hello, world!" (::ai/content entity)))
      (is (= "assistant" (::claude/message-type entity)))
      (is (= "msg-123" (::claude/uuid entity)))
      (is (inst? (::ai/timestamp entity))))))

(deftest normalize-message-with-session-id-test
  (testing "Normalizes Claude message with session-id attached"
    (let [sdk-msg {:type "assistant"
                   :message {:role "assistant"
                             :content [{:type "text" :text "Working on it..."}]}}
          entity (agent/normalize-message {:provider :claude
                                           :message sdk-msg
                                           :session-id "ses-test123"})]
      (is (= "ses-test123" (::ai/session-id entity)))
      (is (= "assistant" (::ai/role entity))))))

(deftest normalize-message-user-test
  (testing "Normalizes Claude user message"
    (let [sdk-msg {:type "user"
                   :message {:role "user"
                             :content [{:type "text" :text "What is 2+2?"}]}}
          entity (agent/normalize-message {:provider :claude
                                           :message sdk-msg})]
      (is (= "user" (::ai/role entity)))
      (is (= "What is 2+2?" (::ai/content entity)))
      (is (= "user" (::claude/message-type entity))))))

(deftest normalize-message-result-test
  (testing "Normalizes Claude result message"
    (let [sdk-msg {:type "result"
                   :result "Task completed successfully"
                   :subtype "success"
                   :num_turns 5
                   :total_cost_usd 0.05}
          entity (agent/normalize-message {:provider :claude
                                           :message sdk-msg})]
      (is (= "assistant" (::ai/role entity)))
      (is (= "Task completed successfully" (::ai/content entity)))
      (is (= "result" (::claude/message-type entity))))))

(deftest normalize-message-tool-use-test
  (testing "Normalizes Claude assistant message with tool calls"
    (let [sdk-msg {:type "assistant"
                   :message {:role "assistant"
                             :content [{:type "text" :text "Let me read that file."}
                                       {:type "tool_use"
                                        :id "tool-abc"
                                        :name "Read"
                                        :input {:file_path "/tmp/test.txt"}}]}}
          entity (agent/normalize-message {:provider :claude
                                           :message sdk-msg})]
      (is (= "Let me read that file." (::ai/content entity)))
      (is (= 1 (count (::claude/tool-calls entity))))
      (let [tool-call (first (::claude/tool-calls entity))]
        (is (= "tool-abc" (:id tool-call)))
        (is (= "Read" (:name tool-call)))))))

;;; ---------------------------------------------------------------------------
;;; result-message? Tests (Claude Provider)
;;; ---------------------------------------------------------------------------

(deftest result-message-true-test
  (testing "Returns true for result message"
    (is (true? (agent/result-message? {:provider :claude
                                       :message {:type "result"
                                                 :subtype "success"}})))))

(deftest result-message-false-test
  (testing "Returns false for non-result messages"
    (is (false? (agent/result-message? {:provider :claude
                                        :message {:type "assistant"}})))
    (is (false? (agent/result-message? {:provider :claude
                                        :message {:type "user"}})))
    (is (false? (agent/result-message? {:provider :claude
                                        :message {:type "system"}})))
    (is (false? (agent/result-message? {:provider :claude
                                        :message {:type "keep_alive"}})))))

;;; ---------------------------------------------------------------------------
;;; parse-result Tests (Claude Provider)
;;; ---------------------------------------------------------------------------

(deftest parse-result-success-test
  (testing "Parses successful result"
    (let [result-msg {:type "result"
                      :subtype "success"
                      :result "All done!"
                      :total_cost_usd 0.05
                      :num_turns 10
                      :duration_ms 5000}
          parsed (agent/parse-result {:provider :claude
                                      :message result-msg})]
      (is (= :completed (::agent/status parsed)))
      (is (= 0.05 (::agent/cost-usd parsed)))
      (is (= 10 (::agent/num-turns parsed)))
      (is (= 5000 (::agent/duration-ms parsed)))
      (is (= "All done!" (::agent/result-text parsed)))
      (is (= "success" (::agent/subtype parsed)))
      ;; Verify it validates against schema
      (is (m/validate ::agent/parsed-result parsed)))))

(deftest parse-result-interrupted-test
  (testing "Parses interrupted result"
    (let [result-msg {:type "result"
                      :subtype "interrupted"
                      :result "Stopped by user"
                      :total_cost_usd 0.02
                      :num_turns 3}
          parsed (agent/parse-result {:provider :claude
                                      :message result-msg})]
      (is (= :interrupted (::agent/status parsed)))
      (is (= "interrupted" (::agent/subtype parsed)))
      (is (m/validate ::agent/parsed-result parsed)))))

(deftest parse-result-error-execution-test
  (testing "Parses error_during_execution result"
    (let [result-msg {:type "result"
                      :subtype "error_during_execution"
                      :result "Error: something went wrong"}
          parsed (agent/parse-result {:provider :claude
                                      :message result-msg})]
      (is (= :failed (::agent/status parsed)))
      (is (= "error_during_execution" (::agent/subtype parsed)))
      (is (m/validate ::agent/parsed-result parsed)))))

(deftest parse-result-error-max-turns-test
  (testing "Parses error_max_turns result"
    (let [result-msg {:type "result"
                      :subtype "error_max_turns"
                      :num_turns 100}
          parsed (agent/parse-result {:provider :claude
                                      :message result-msg})]
      (is (= :failed (::agent/status parsed)))
      (is (= "error_max_turns" (::agent/subtype parsed)))
      (is (= 100 (::agent/num-turns parsed)))
      (is (m/validate ::agent/parsed-result parsed)))))

(deftest parse-result-error-max-budget-test
  (testing "Parses error_max_budget_usd result"
    (let [result-msg {:type "result"
                      :subtype "error_max_budget_usd"
                      :total_cost_usd 5.0}
          parsed (agent/parse-result {:provider :claude
                                      :message result-msg})]
      (is (= :failed (::agent/status parsed)))
      (is (= "error_max_budget_usd" (::agent/subtype parsed)))
      (is (= 5.0 (::agent/cost-usd parsed)))
      (is (m/validate ::agent/parsed-result parsed)))))

(deftest parse-result-unknown-subtype-test
  (testing "Parses unknown subtype as :error status"
    (let [result-msg {:type "result"
                      :subtype "unknown_new_subtype"}
          parsed (agent/parse-result {:provider :claude
                                      :message result-msg})]
      (is (= :error (::agent/status parsed)))
      (is (= "unknown_new_subtype" (::agent/subtype parsed)))
      (is (m/validate ::agent/parsed-result parsed)))))

(deftest parse-result-minimal-test
  (testing "Parses minimal result with only status"
    (let [result-msg {:type "result"
                      :subtype "success"}
          parsed (agent/parse-result {:provider :claude
                                      :message result-msg})]
      (is (= :completed (::agent/status parsed)))
      (is (= "success" (::agent/subtype parsed)))
      (is (nil? (::agent/cost-usd parsed)))
      (is (nil? (::agent/num-turns parsed)))
      (is (m/validate ::agent/parsed-result parsed)))))

;;; ---------------------------------------------------------------------------
;;; Default Implementation Tests
;;; ---------------------------------------------------------------------------

(deftest default-normalize-message-throws-test
  (testing "Default normalize-message throws for unknown provider"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No normalize-message implementation for provider: :unknown-provider"
         (agent/normalize-message {:provider :unknown-provider
                                   :message {}})))))

(deftest default-result-message-throws-test
  (testing "Default result-message? throws for unknown provider"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No result-message\? implementation for provider: :unknown-provider"
         (agent/result-message? {:provider :unknown-provider
                                 :message {}})))))

(deftest default-parse-result-throws-test
  (testing "Default parse-result throws for unknown provider"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No parse-result implementation for provider: :unknown-provider"
         (agent/parse-result {:provider :unknown-provider
                              :message {}})))))

;;; ---------------------------------------------------------------------------
;;; Development Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.ai.agent-test)

  ;; Run specific test
  (clojure.test/test-var #'schema-registration-test)
  (clojure.test/test-var #'normalize-message-assistant-test)
  (clojure.test/test-var #'result-message-true-test)
  (clojure.test/test-var #'parse-result-success-test)

  nil)
