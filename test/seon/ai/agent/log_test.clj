(ns seon.ai.agent.log-test
  "Tests for structured agent logging."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.ai.agent.log :as agent-log]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-dir* "tmp/test-logs")

(defn with-test-dir
  "Create and clean up test directory."
  [f]
  (let [dir (io/file *test-dir*)]
    (.mkdirs dir)
    (try
      (f)
      (finally
        ;; Clean up test files
        (doseq [file (.listFiles dir)]
          (.delete file))
        (.delete dir)))))

(use-fixtures :each with-test-dir)

;;; ---------------------------------------------------------------------------
;;; Helper Functions
;;; ---------------------------------------------------------------------------

(defn read-log-lines
  "Read all lines from a log file."
  [path]
  (when (.exists (io/file path))
    (str/split-lines (slurp path))))

(defn parse-log-line
  "Parse a log line into components: [timestamp event & fields]"
  [line]
  (mapv str/trim (str/split line #"\|")))

;;; ---------------------------------------------------------------------------
;;; Unit Tests
;;; ---------------------------------------------------------------------------

(deftest create-logger-test
  (testing "create-logger! creates log file and returns handle"
    (let [logger (agent-log/create-logger! {::agent-log/session-id "test-001"})]
      (try
        (is (some? (::agent-log/writer logger)))
        (is (= "test-001" (::agent-log/session-id logger)))
        (is (str/ends-with? (::agent-log/path logger) "test-001.log"))
        (finally
          (agent-log/close-logger! logger))))))

(deftest log-launch-test
  (testing "log-launch! writes LAUNCH event"
    (let [logger (agent-log/create-logger! {::agent-log/session-id "test-002"})]
      (try
        (agent-log/log-launch! logger {::agent-log/namespace "seon.trading"
                                        ::agent-log/port 7892})
        (agent-log/close-logger! logger)
        (let [lines (read-log-lines (::agent-log/path logger))
              [ts event ns-field port-field] (parse-log-line (first lines))]
          (is (= 1 (count lines)))
          (is (str/ends-with? ts "Z") "Timestamp should be ISO format")
          (is (= "LAUNCH" event))
          (is (= "seon.trading" ns-field))
          (is (= "port=7892" port-field)))
        (finally
          (io/delete-file (::agent-log/path logger) true))))))

(deftest log-message-test
  (testing "log-message! writes MESSAGE event with full content (no truncation)"
    (let [logger (agent-log/create-logger! {::agent-log/session-id "test-003"})
          long-content (apply str (repeat 500 "x"))]
      (try
        (agent-log/log-message! logger {::agent-log/role "assistant"
                                         ::agent-log/content long-content})
        (agent-log/close-logger! logger)
        (let [lines (read-log-lines (::agent-log/path logger))
              [_ event role content] (parse-log-line (first lines))]
          (is (= "MESSAGE" event))
          (is (= "assistant" role))
          ;; Full content preserved - UI handles display truncation
          (is (str/includes? content "xxxxx") "Content should be preserved"))
        (finally
          (io/delete-file (::agent-log/path logger) true))))))

(deftest log-tool-test
  (testing "log-tool! writes TOOL event"
    (let [logger (agent-log/create-logger! {::agent-log/session-id "test-004"})]
      (try
        (agent-log/log-tool! logger {::agent-log/tool-name "eval"
                                      ::agent-log/input "(xt/q node \"SELECT * FROM ai_sessions\")"})
        (agent-log/close-logger! logger)
        (let [lines (read-log-lines (::agent-log/path logger))
              [_ event tool-name _] (parse-log-line (first lines))]
          (is (= "TOOL" event))
          (is (= "eval" tool-name)))
        (finally
          (io/delete-file (::agent-log/path logger) true))))))

(deftest log-result-test
  (testing "log-result! writes RESULT event"
    (let [logger (agent-log/create-logger! {::agent-log/session-id "test-005"})]
      (try
        (agent-log/log-result! logger {::agent-log/tool-name "eval"
                                        ::agent-log/output "[{:_id \"ses-abc\"}]"})
        (agent-log/close-logger! logger)
        (let [lines (read-log-lines (::agent-log/path logger))
              [_ event tool-name _] (parse-log-line (first lines))]
          (is (= "RESULT" event))
          (is (= "eval" tool-name)))
        (finally
          (io/delete-file (::agent-log/path logger) true))))))

(deftest log-complete-test
  (testing "log-complete! writes COMPLETE event with stats"
    (let [logger (agent-log/create-logger! {::agent-log/session-id "test-006"})]
      (try
        (agent-log/log-complete! logger {::agent-log/cost 0.45
                                          ::agent-log/messages 84
                                          ::agent-log/duration-ms 100000
                                          ::agent-log/subtype "success"})
        (agent-log/close-logger! logger)
        (let [lines (read-log-lines (::agent-log/path logger))
              line (first lines)]
          (is (str/includes? line "COMPLETE"))
          (is (str/includes? line "cost=$0.45"))
          (is (str/includes? line "messages=84"))
          (is (str/includes? line "duration=100s"))
          (is (str/includes? line "subtype=success")))
        (finally
          (io/delete-file (::agent-log/path logger) true))))))

(deftest log-sdk-message-test
  (testing "log-sdk-message! handles assistant message with tool calls"
    (let [logger (agent-log/create-logger! {::agent-log/session-id "test-007"})
          sdk-msg {:type "assistant"
                   :message {:role "assistant"
                             :content [{:type "text" :text "I'll analyze the data."}
                                       {:type "tool_use"
                                        :name "eval"
                                        :input {:code "(+ 1 2)"}}]}}]
      (try
        (agent-log/log-sdk-message! logger sdk-msg)
        (agent-log/close-logger! logger)
        (let [lines (read-log-lines (::agent-log/path logger))]
          (is (= 2 (count lines)) "Should log both message and tool call")
          (is (str/includes? (first lines) "MESSAGE"))
          (is (str/includes? (second lines) "TOOL")))
        (finally
          (io/delete-file (::agent-log/path logger) true)))))

  (testing "log-sdk-message! handles result message"
    (let [logger (agent-log/create-logger! {::agent-log/session-id "test-008"})
          sdk-msg {:type "result"
                   :subtype "success"
                   :total_cost_usd 0.05
                   :num_turns 10
                   :duration_ms 5000}]
      (try
        (agent-log/log-sdk-message! logger sdk-msg)
        (agent-log/close-logger! logger)
        (let [lines (read-log-lines (::agent-log/path logger))
              line (first lines)]
          (is (= 1 (count lines)))
          (is (str/includes? line "COMPLETE"))
          (is (str/includes? line "success")))
        (finally
          (io/delete-file (::agent-log/path logger) true)))))

  (testing "log-sdk-message! skips keep_alive messages"
    (let [logger (agent-log/create-logger! {::agent-log/session-id "test-009"})
          sdk-msg {:type "keep_alive"}]
      (try
        (agent-log/log-sdk-message! logger sdk-msg)
        (agent-log/close-logger! logger)
        (let [content (slurp (::agent-log/path logger))]
          (is (str/blank? content) "keep_alive should not be logged"))
        (finally
          (io/delete-file (::agent-log/path logger) true))))))

(deftest newline-handling-test
  (testing "Content with newlines is escaped to single line"
    (let [logger (agent-log/create-logger! {::agent-log/session-id "test-010"})
          multi-line-content "Line 1\nLine 2\nLine 3"]
      (try
        (agent-log/log-message! logger {::agent-log/role "assistant"
                                         ::agent-log/content multi-line-content})
        (agent-log/close-logger! logger)
        (let [lines (read-log-lines (::agent-log/path logger))]
          (is (= 1 (count lines)) "Should be a single line")
          (is (not (str/includes? (first lines) "\n")) "Should not contain newlines"))
        (finally
          (io/delete-file (::agent-log/path logger) true))))))

(comment
  ;; Run tests
  (clojure.test/run-tests 'seon.ai.agent.log-test)

  nil)
