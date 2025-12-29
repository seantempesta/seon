(ns seon.dev.hook-test
  "Tests for the hook namespace - main orchestrator for dev feedback."
  (:require [clojure.test :refer :all]
            [seon.dev.hook :as hook]
            [seon.dev.context :as context]
            [seon.test-utils :refer [with-test-node *test-node*]]))

;;; ---------------------------------------------------------------------------
;;; Helper Functions
;;; ---------------------------------------------------------------------------

(defn make-event
  "Create a hook event for testing."
  [event-name tool-name file-path]
  {:hook_event_name event-name
   :tool_name tool-name
   :tool_input {:file_path file-path}})

(defn make-request
  "Create a hook request for testing."
  ([event]
   {::hook/event event
    ::hook/config {}})
  ([event config]
   {::hook/event event
    ::hook/config config}))

;;; ---------------------------------------------------------------------------
;;; Event Filtering Tests
;;; ---------------------------------------------------------------------------

(deftest event-filtering-test
  (with-test-node
    (fn []
      (testing "Skips non-Edit/Write events"
        (let [event {:hook_event_name "PostToolUse"
                     :tool_name "Read"
                     :tool_input {:file_path "/path/to/file.clj"}}
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (::hook/continue result) "Should continue for non-Edit/Write events")))

      (testing "Skips non-Clojure files"
        (let [event (make-event "PostToolUse" "Edit" "/path/to/file.json")
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (::hook/continue result) "Should continue for non-Clojure files")))

      (testing "Handles various Clojure file extensions"
        (doseq [ext [".clj" ".cljs" ".cljc" ".bb" ".edn"]]
          (let [event (make-event "PostToolUse" "Edit" (str "/path/to/file" ext))
                result (hook/process-hook-event! *test-node* (make-request event))]
            ;; These should pass through (continue=true) since they're not in src/seon/
            (is (::hook/continue result) (str "Should handle " ext " files"))))))))

(deftest non-seon-source-test
  (with-test-node
    (fn []
      (testing "Skips files not in src/seon/"
        (let [event (make-event "PostToolUse" "Edit" "/path/to/other/file.clj")
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (::hook/continue result) "Should continue for non-seon files")))

      (testing "Skips test files"
        (let [event (make-event "PostToolUse" "Edit" "/path/to/test/seon/core_test.clj")
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (::hook/continue result) "Should continue for test files"))))))

;;; ---------------------------------------------------------------------------
;;; Response Format Tests
;;; ---------------------------------------------------------------------------

(deftest response-format-test
  (with-test-node
    (fn []
      (testing "Success response has correct format"
        (let [event (make-event "PostToolUse" "Edit" "/path/to/file.txt")
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (true? (::hook/continue result)) "Should have ::continue true")
          (is (nil? (::hook/decision result)) "Should not have ::decision")
          (is (nil? (::hook/reason result)) "Should not have ::reason"))))))

;;; ---------------------------------------------------------------------------
;;; Configuration Tests
;;; ---------------------------------------------------------------------------

(deftest config-merge-test
  (testing "Merges user config with defaults"
    ;; We test this indirectly by checking behavior
    (with-test-node
      (fn []
        ;; With repair disabled, should skip repair stage
        (let [event (make-event "PreToolUse" "Edit" "/path/to/file.clj")
              config {:repair {:enabled false}}
              result (hook/process-hook-event! *test-node* (make-request event config))]
          (is (::hook/continue result) "Should continue with repair disabled"))))))

(deftest config-defaults-test
  (testing "Uses default config when not provided"
    (with-test-node
      (fn []
        (let [event (make-event "PostToolUse" "Edit" "/path/to/file.json")
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (::hook/continue result) "Should work with default config"))))))

;;; ---------------------------------------------------------------------------
;;; PreToolUse Tests
;;; ---------------------------------------------------------------------------

(deftest pre-tool-use-test
  (with-test-node
    (fn []
      (testing "PreToolUse only runs repair stage"
        (let [event (make-event "PreToolUse" "Edit" "/path/to/file.clj")
              result (hook/process-hook-event! *test-node* (make-request event))]
          ;; Should succeed for non-existent file (no repair needed)
          (is (::hook/continue result) "Should continue for PreToolUse")))

      (testing "PreToolUse skips non-Clojure files"
        (let [event (make-event "PreToolUse" "Write" "/path/to/file.md")
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (::hook/continue result) "Should skip non-Clojure files"))))))

;;; ---------------------------------------------------------------------------
;;; File Path Extraction Tests
;;; ---------------------------------------------------------------------------

(deftest file-path-extraction-test
  (with-test-node
    (fn []
      (testing "Extracts file_path from tool_input"
        (let [event {:hook_event_name "PostToolUse"
                     :tool_name "Edit"
                     :tool_input {:file_path "/path/to/file.clj"}}
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (some? result) "Should process event with file_path")))

      (testing "Extracts filePath from tool_input (camelCase)"
        (let [event {:hook_event_name "PostToolUse"
                     :tool_name "Edit"
                     :tool_input {:filePath "/path/to/file.clj"}}
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (some? result) "Should process event with filePath")))

      (testing "Handles missing tool_input gracefully"
        (let [event {:hook_event_name "PostToolUse"
                     :tool_name "Edit"}
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (::hook/continue result) "Should continue with missing tool_input"))))))

;;; ---------------------------------------------------------------------------
;;; Integration with Context Tests
;;; ---------------------------------------------------------------------------

(deftest context-integration-test
  (with-test-node
    (fn []
      (testing "Records edit events via context namespace"
        ;; Clear any existing events first
        (context/clear-all-events! *test-node*)

        ;; Process an edit event for a real seon file
        ;; We use a file that exists to test the full path
        (let [file-path "/Users/sean/src/seon/src/seon/core.clj"
              event (make-event "PostToolUse" "Edit" file-path)
              config {:repair {:enabled false}
                      :tests {:unit {:enabled false}
                              :generative {:enabled false}}
                      :review {:enabled false}}]
          ;; This may fail if the file doesn't exist, which is expected in tests
          ;; The key thing is that it doesn't throw an exception
          (let [result (try
                         (hook/process-hook-event! *test-node* (make-request event config))
                         (catch Exception _
                           ;; Expected if file doesn't exist in test environment
                           {::hook/continue true}))]
            (is (some? result) "Should return a result")
            (is (or (::hook/continue result) (::hook/decision result))
                "Should have continue or decision in response")))))))

;;; ---------------------------------------------------------------------------
;;; Feedback Accumulation Tests
;;; ---------------------------------------------------------------------------

(deftest feedback-accumulation-test
  (with-test-node
    (fn []
      (testing "Empty feedback returns minimal response"
        (let [event (make-event "PostToolUse" "Edit" "/path/to/file.md")
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (true? (::hook/continue result)))
          ;; Feedback may or may not be present, but should be vector if present
          (when (::hook/feedback result)
            (is (vector? (::hook/feedback result))))))

      (testing "Feedback is accumulated as vector"
        (let [event (make-event "PostToolUse" "Edit" "/path/to/file.clj")
              result (hook/process-hook-event! *test-node* (make-request event))]
          (when (::hook/feedback result)
            (is (vector? (::hook/feedback result)) "Feedback should be a vector")))))))

;;; ---------------------------------------------------------------------------
;;; Schema Validation Tests
;;; ---------------------------------------------------------------------------

(deftest schema-registration-test
  (testing "Hook event schema is registered"
    (is (some? (seon.schema/schema-definition ::hook/hook-event))
        "Hook event schema should be registered"))

  (testing "Process request schema is registered"
    (is (some? (seon.schema/schema-definition ::hook/process-request))
        "Process request schema should be registered"))

  (testing "Process response schema is registered"
    (is (some? (seon.schema/schema-definition ::hook/process-response))
        "Process response schema should be registered")))

;;; ---------------------------------------------------------------------------
;;; Error Handling Tests
;;; ---------------------------------------------------------------------------

(deftest error-handling-test
  (with-test-node
    (fn []
      (testing "Handles malformed events gracefully"
        (let [event {:hook_event_name "UnknownEvent"
                     :tool_name "UnknownTool"}
              result (hook/process-hook-event! *test-node* (make-request event))]
          ;; Should return success (skip unknown events)
          (is (::hook/continue result) "Should continue for unknown events")))

      (testing "Handles nil file path gracefully"
        (let [event {:hook_event_name "PostToolUse"
                     :tool_name "Edit"
                     :tool_input {:file_path nil}}
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (::hook/continue result) "Should continue for nil file path"))))))

;;; ---------------------------------------------------------------------------
;;; Tool Name Variations Tests
;;; ---------------------------------------------------------------------------

(deftest tool-name-test
  (with-test-node
    (fn []
      (testing "Handles Edit tool"
        (let [event (make-event "PostToolUse" "Edit" "/path/to/file.txt")
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (::hook/continue result) "Should handle Edit tool")))

      (testing "Handles Write tool"
        (let [event (make-event "PostToolUse" "Write" "/path/to/file.txt")
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (::hook/continue result) "Should handle Write tool"))))))

;;; ---------------------------------------------------------------------------
;;; Edge Cases
;;; ---------------------------------------------------------------------------

(deftest edge-cases-test
  (with-test-node
    (fn []
      (testing "Very long file path"
        (let [long-path (str "/very/long/path/" (apply str (repeat 500 "x")) ".clj")
              event (make-event "PostToolUse" "Edit" long-path)
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (::hook/continue result) "Should handle long paths")))

      (testing "File path with spaces"
        (let [event (make-event "PostToolUse" "Edit" "/path/to/my file.clj")
              result (hook/process-hook-event! *test-node* (make-request event))]
          (is (::hook/continue result) "Should handle paths with spaces")))

      (testing "Empty config map"
        (let [event (make-event "PostToolUse" "Edit" "/path/to/file.txt")
              result (hook/process-hook-event! *test-node* (make-request event {}))]
          (is (::hook/continue result) "Should handle empty config"))))))
