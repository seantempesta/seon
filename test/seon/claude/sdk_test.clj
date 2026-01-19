(ns seon.claude.sdk-test
  "DEPRECATED: Tests for the deprecated seon.claude.sdk namespace.

   This test file is maintained for backwards compatibility only.
   New tests should be added to seon.ai.claude-test instead.

   See seon.ai.claude-test for comprehensive tests of the new namespace.

   ---

   LEGACY DOCS (for reference):

   Tests for seon.claude.sdk.

   Note: Integration tests that spawn Claude Code processes are not included
   here as they require external dependencies and incur API costs. Those are
   tested via REPL exploration (see comment block in sdk.clj).

   These tests focus on schema validation and helper functions."
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [seon.claude.sdk :as sdk]))

;;; ---------------------------------------------------------------------------
;;; Schema Validation Tests
;;; ---------------------------------------------------------------------------

(deftest model-schema-test
  (testing "valid model identifiers"
    (is (m/validate ::sdk/model "claude-opus-4-5-20251101"))
    (is (m/validate ::sdk/model "claude-sonnet-4-20250514"))
    (is (m/validate ::sdk/model "claude-3-5-haiku-20241022")))

  (testing "invalid model identifiers"
    (is (not (m/validate ::sdk/model "gpt-4")))
    (is (not (m/validate ::sdk/model "")))
    (is (not (m/validate ::sdk/model nil)))))

(deftest permission-mode-schema-test
  (testing "valid permission modes"
    (is (m/validate ::sdk/permission-mode "default"))
    (is (m/validate ::sdk/permission-mode "acceptEdits"))
    (is (m/validate ::sdk/permission-mode "bypassPermissions"))
    (is (m/validate ::sdk/permission-mode "plan"))
    (is (m/validate ::sdk/permission-mode "dontAsk")))

  (testing "invalid permission modes"
    (is (not (m/validate ::sdk/permission-mode "allow-all")))
    (is (not (m/validate ::sdk/permission-mode "")))))

(deftest query-options-schema-test
  (testing "empty options valid"
    (is (m/validate ::sdk/query-options {})))

  (testing "full options valid"
    (is (m/validate ::sdk/query-options
                    {::sdk/model "claude-opus-4-5-20251101"
                     ::sdk/cwd "/tmp"
                     ::sdk/permission-mode "bypassPermissions"
                     ::sdk/allowed-tools ["Read" "Edit"]
                     ::sdk/disallowed-tools ["Bash"]
                     ::sdk/max-turns 10
                     ::sdk/max-budget-usd 1.0
                     ::sdk/cli-command "/usr/local/bin/claude"})))

  (testing "invalid options rejected"
    (is (not (m/validate ::sdk/query-options
                         {::sdk/max-turns -1})))
    (is (not (m/validate ::sdk/query-options
                         {::sdk/max-budget-usd 500.0})))))

(deftest query-request-schema-test
  (testing "minimal request valid"
    (is (m/validate ::sdk/query-request
                    {::sdk/prompt "Hello"})))

  (testing "request with options valid"
    (is (m/validate ::sdk/query-request
                    {::sdk/prompt "What is 2+2?"
                     ::sdk/options {::sdk/model "claude-opus-4-5-20251101"
                                    ::sdk/max-turns 5}})))

  (testing "missing prompt invalid"
    ;; ::sdk/prompt is :any so anything is valid including nil
    ;; This is intentional to prevent generative testing
    (is (m/validate ::sdk/query-request {::sdk/prompt nil}))))

(deftest result-message-schema-test
  (testing "success result valid"
    (is (m/validate ::sdk/result-message
                    {:type "result"
                     :subtype "success"
                     :result "4"
                     :num_turns 1
                     :total_cost_usd 0.03
                     :duration_ms 5000})))

  (testing "error result valid"
    (is (m/validate ::sdk/result-message
                    {:type "result"
                     :subtype "error_max_turns"
                     :num_turns 50
                     :total_cost_usd 1.5
                     :duration_ms 120000
                     :is_error true})))

  (testing "incomplete result invalid"
    (is (not (m/validate ::sdk/result-message
                         {:type "result"
                          :subtype "success"})))))

(deftest query-handle-schema-test
  (testing "valid handle structure"
    (is (m/validate ::sdk/query-handle
                    {::sdk/messages-ch (Object.)  ; any channel-like object
                     ::sdk/result-ch (Object.)
                     ::sdk/send! (fn [_] nil)
                     ::sdk/close! (fn [] nil)})))

  (testing "missing functions invalid"
    (is (not (m/validate ::sdk/query-handle
                         {::sdk/messages-ch (Object.)
                          ::sdk/result-ch (Object.)})))))

;;; ---------------------------------------------------------------------------
;;; Default Configuration Tests
;;; ---------------------------------------------------------------------------

(deftest default-values-test
  (testing "default model is Opus 4.5"
    (is (= "claude-opus-4-5-20251101" sdk/default-model)))

  (testing "default permission mode is 'default'"
    (is (= "default" sdk/default-permission-mode)))

  (testing "default CLI command is set"
    (is (= "/opt/homebrew/bin/claude" sdk/default-cli-command))))
