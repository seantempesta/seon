(ns seon.ai.claude-test
  "Tests for seon.ai.claude provider namespace.

   Tests cover:
   1. Schema registration - Claude-specific schemas are in global registry
   2. Schema generation - can generate valid sample data
   3. Schema composition - Claude schemas reference base seon.ai schemas
   4. sdk-message->entity conversion - SDK messages convert to entities correctly
   5. Persistence integration - persist-message! stores entities correctly"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [malli.core :as m]
   [malli.generator :as mg]
   [seon.ai :as ai]
   [seon.ai.claude :as claude]
   [seon.schema :as schema]
   [seon.test-utils :refer [with-test-node *test-node*]])
  (:import [java.time Instant ZonedDateTime]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(use-fixtures :each with-test-node)

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn temporal?
  "Check if value is a temporal type (Instant or ZonedDateTime).
   XTDB returns ZonedDateTime for timestamps, not Instant."
  [v]
  (or (instance? Instant v)
      (instance? ZonedDateTime v)))

;;; ---------------------------------------------------------------------------
;;; Schema Registration Tests
;;; ---------------------------------------------------------------------------

(deftest schema-registration-test
  (testing "Claude schemas are registered in global registry"
    (let [claude-schemas (schema/schemas-in-namespace "seon.ai.claude")]
      (is (pos? (count claude-schemas)) "Should have registered schemas")
      ;; Core Claude schemas
      (is (contains? claude-schemas ::claude/model))
      (is (contains? claude-schemas ::claude/cache-creation-tokens))
      (is (contains? claude-schemas ::claude/cache-read-tokens))
      (is (contains? claude-schemas ::claude/tool-calls))
      (is (contains? claude-schemas ::claude/tool-results))
      (is (contains? claude-schemas ::claude/message-type))
      (is (contains? claude-schemas ::claude/uuid))
      (is (contains? claude-schemas ::claude/raw-message))
      ;; Composite schemas
      (is (contains? claude-schemas ::claude/sdk-message))
      (is (contains? claude-schemas ::claude/message-entity))
      (is (contains? claude-schemas ::claude/agent-handle))
      (is (contains? claude-schemas ::claude/agent-summary)))))

(deftest schema-validity-test
  (testing "All registered Claude schemas are valid Malli schemas"
    (doseq [[k _] (schema/schemas-in-namespace "seon.ai.claude")]
      (is (m/schema? (m/schema k))
          (str "Schema " k " should be a valid Malli schema")))))

;;; ---------------------------------------------------------------------------
;;; Schema Generation Tests
;;; ---------------------------------------------------------------------------

(deftest schema-generation-test
  (testing "Can generate ::claude/model"
    (let [generated (mg/generate ::claude/model)]
      (is (#{"claude-opus-4-5-20251101"
             "claude-sonnet-4-20250514"
             "claude-3-5-haiku-20241022"} generated))))

  (testing "Can generate ::claude/message-type"
    (let [generated (mg/generate ::claude/message-type)]
      (is (#{"system" "assistant" "user" "result" "keep_alive" "parse_error"} generated))))

  (testing "Can generate ::claude/agent-status"
    (let [generated (mg/generate ::claude/agent-status)]
      (is (#{:running :completed :failed :terminated :interrupted} generated))))

  (testing "Can generate ::claude/cache-creation-tokens"
    (let [generated (mg/generate ::claude/cache-creation-tokens)]
      (is (int? generated))
      (is (>= generated 0))))

  (testing "Can generate ::claude/cache-read-tokens"
    (let [generated (mg/generate ::claude/cache-read-tokens)]
      (is (int? generated))
      (is (>= generated 0))))

  (testing "Can generate ::claude/permission-mode"
    (let [generated (mg/generate ::claude/permission-mode)]
      (is (#{"default" "acceptEdits" "bypassPermissions" "plan" "dontAsk"} generated)))))

(deftest complex-schema-generation-test
  (testing "Can generate multiple valid models"
    (let [samples (mg/sample ::claude/model {:size 10})]
      (is (= 10 (count samples)))
      (doseq [model samples]
        (is (m/validate ::claude/model model)))))

  (testing "Can generate multiple valid message types"
    (let [samples (mg/sample ::claude/message-type {:size 10})]
      (is (= 10 (count samples)))
      (doseq [msg-type samples]
        (is (m/validate ::claude/message-type msg-type)))))

  (testing "Can generate sdk-message"
    (let [samples (mg/sample ::claude/sdk-message {:size 5})]
      (is (= 5 (count samples)))
      (doseq [msg samples]
        (is (m/validate ::claude/sdk-message msg))))))

;;; ---------------------------------------------------------------------------
;;; Schema Composition Tests (Claude references base seon.ai)
;;; ---------------------------------------------------------------------------

(deftest schema-composition-test
  (testing "message-entity references base seon.ai schemas"
    ;; Verify that the message-entity schema exists and can be resolved
    (let [msg-entity-schema (m/schema ::claude/message-entity)]
      (is (some? msg-entity-schema))
      (is (m/schema? msg-entity-schema))
      ;; Check the schema definition in our registry references base schemas
      (let [schema-def (schema/schema-definition ::claude/message-entity)
            schema-str (pr-str schema-def)]
        (is (clojure.string/includes? schema-str ":seon.ai/type"))
        (is (clojure.string/includes? schema-str ":seon.ai/role"))
        (is (clojure.string/includes? schema-str ":seon.ai/content"))
        (is (clojure.string/includes? schema-str ":seon.ai/timestamp")))))

  (testing "agent-summary references base seon.ai session-id"
    (let [summary-schema (m/schema ::claude/agent-summary)]
      (is (some? summary-schema))
      (is (m/schema? summary-schema))
      ;; Check the schema definition in our registry
      (let [schema-def (schema/schema-definition ::claude/agent-summary)
            schema-str (pr-str schema-def)]
        (is (clojure.string/includes? schema-str ":seon.ai/session-id"))
        (is (clojure.string/includes? schema-str ":seon.ai/namespace"))))))

;;; ---------------------------------------------------------------------------
;;; sdk-message->entity Conversion Tests
;;; ---------------------------------------------------------------------------

(deftest sdk-message-to-entity-basic-test
  (testing "Converts assistant text message"
    (let [sdk-msg {:type "assistant"
                   :uuid "msg-123"
                   :message {:role "assistant"
                             :content [{:type "text" :text "Hello, world!"}]}}
          entity (claude/sdk-message->entity {::claude/sdk-message sdk-msg})]
      (is (string? (:xt/id entity)))
      (is (clojure.string/starts-with? (:xt/id entity) "msg-"))
      (is (= :message (::ai/type entity)))
      (is (= "assistant" (::ai/role entity)))
      (is (= "Hello, world!" (::ai/content entity)))
      (is (= "assistant" (::claude/message-type entity)))
      (is (= "msg-123" (::claude/uuid entity)))
      (is (inst? (::ai/timestamp entity)))))

  (testing "Converts user message"
    (let [sdk-msg {:type "user"
                   :message {:role "user"
                             :content [{:type "text" :text "What is 2+2?"}]}}
          entity (claude/sdk-message->entity {::claude/sdk-message sdk-msg})]
      (is (= "user" (::ai/role entity)))
      (is (= "What is 2+2?" (::ai/content entity)))
      (is (= "user" (::claude/message-type entity)))))

  (testing "Converts system message"
    (let [sdk-msg {:type "system"
                   :message {:role "system"
                             :content [{:type "text" :text "You are a helpful assistant."}]}}
          entity (claude/sdk-message->entity {::claude/sdk-message sdk-msg})]
      (is (= "system" (::ai/role entity)))
      (is (= "system" (::claude/message-type entity)))))

  (testing "Converts result message"
    (let [sdk-msg {:type "result"
                   :result "Task completed successfully"
                   :subtype "success"
                   :num_turns 5
                   :total_cost_usd 0.05}
          entity (claude/sdk-message->entity {::claude/sdk-message sdk-msg})]
      (is (= "assistant" (::ai/role entity)))  ; result maps to assistant
      (is (= "Task completed successfully" (::ai/content entity)))
      (is (= "result" (::claude/message-type entity))))))

(deftest sdk-message-to-entity-tool-use-test
  (testing "Extracts tool_use blocks from assistant message"
    (let [sdk-msg {:type "assistant"
                   :message {:role "assistant"
                             :content [{:type "text" :text "Let me read that file."}
                                       {:type "tool_use"
                                        :id "tool-abc"
                                        :name "Read"
                                        :input {:file_path "/tmp/test.txt"}}]}}
          entity (claude/sdk-message->entity {::claude/sdk-message sdk-msg})]
      (is (= "Let me read that file." (::ai/content entity)))
      (is (= 1 (count (::claude/tool-calls entity))))
      (let [tool-call (first (::claude/tool-calls entity))]
        (is (= "tool-abc" (:id tool-call)))
        (is (= "Read" (:name tool-call)))
        (is (= {:file_path "/tmp/test.txt"} (:input tool-call))))))

  (testing "Extracts tool_result blocks from user message"
    (let [sdk-msg {:type "user"
                   :message {:role "user"
                             :content [{:type "tool_result"
                                        :tool_use_id "tool-abc"
                                        :content "File contents here"}]}}
          entity (claude/sdk-message->entity {::claude/sdk-message sdk-msg})]
      (is (= 1 (count (::claude/tool-results entity))))
      (let [tool-result (first (::claude/tool-results entity))]
        (is (= "tool-abc" (:tool_use_id tool-result)))
        (is (= "File contents here" (:content tool-result)))))))

(deftest sdk-message-to-entity-multiple-text-test
  (testing "Joins multiple text blocks"
    (let [sdk-msg {:type "assistant"
                   :message {:role "assistant"
                             :content [{:type "text" :text "First part."}
                                       {:type "text" :text "Second part."}]}}
          entity (claude/sdk-message->entity {::claude/sdk-message sdk-msg})]
      (is (= "First part.\nSecond part." (::ai/content entity))))))

(deftest sdk-message-to-entity-edge-cases-test
  (testing "Handles empty content"
    (let [sdk-msg {:type "assistant"
                   :message {:role "assistant"
                             :content []}}
          entity (claude/sdk-message->entity {::claude/sdk-message sdk-msg})]
      (is (= "" (::ai/content entity)))))

  (testing "Handles string content directly"
    (let [sdk-msg {:type "assistant"
                   :message {:role "assistant"
                             :content "Direct string content"}}
          entity (claude/sdk-message->entity {::claude/sdk-message sdk-msg})]
      (is (= "Direct string content" (::ai/content entity)))))

  (testing "Handles missing uuid gracefully"
    (let [sdk-msg {:type "assistant"
                   :message {:role "assistant"
                             :content [{:type "text" :text "No UUID"}]}}
          entity (claude/sdk-message->entity {::claude/sdk-message sdk-msg})]
      (is (nil? (::claude/uuid entity))))))

;;; ---------------------------------------------------------------------------
;;; Constants Tests
;;; ---------------------------------------------------------------------------

(deftest constants-test
  (testing "Default CLI command is set"
    (is (= "/opt/homebrew/bin/claude" claude/default-cli-command)))

  (testing "Default model is valid"
    (is (m/validate ::claude/model claude/default-model)))

  (testing "Default permission mode is valid"
    (is (m/validate ::claude/permission-mode claude/default-permission-mode))))

;;; ---------------------------------------------------------------------------
;;; Agent Registry Tests (unit tests without spawning processes)
;;; ---------------------------------------------------------------------------

(deftest agents-empty-test
  (testing "agents returns empty vector when no agents running"
    ;; This tests the agents function without any active agents
    ;; (actual agent launching requires XTDB and process spawning)
    (let [result (claude/agents {})]
      (is (vector? result)))))

;;; ---------------------------------------------------------------------------
;;; Persistence Integration Tests
;;; ---------------------------------------------------------------------------

(deftest sdk-message-to-entity-with-session-id-test
  (testing "sdk-message->entity includes session-id when provided"
    (let [sdk-msg {:type "assistant"
                   :uuid "msg-123"
                   :message {:role "assistant"
                             :content [{:type "text" :text "Hello!"}]}}
          entity (claude/sdk-message->entity {::claude/sdk-message sdk-msg
                                               ::ai/session-id "ses-test123"})]
      (is (= "ses-test123" (::ai/session-id entity)))
      (is (= :message (::ai/type entity)))
      (is (= "assistant" (::ai/role entity)))))

  (testing "sdk-message->entity omits session-id when not provided"
    (let [sdk-msg {:type "assistant"
                   :message {:role "assistant"
                             :content [{:type "text" :text "Hello!"}]}}
          entity (claude/sdk-message->entity {::claude/sdk-message sdk-msg})]
      (is (nil? (::ai/session-id entity))))))

(deftest persist-message-test
  (testing "persist-message! stores SDK message in XTDB"
    ;; Create an AI session first
    (let [{session-id ::ai/session-id}
          (ai/start-session! {::ai/node *test-node*
                              ::ai/namespace 'seon.test
                              ::ai/prompt "Test prompt"})
          ;; Persist an assistant message
          sdk-msg {:type "assistant"
                   :uuid "msg-abc123"
                   :message {:role "assistant"
                             :content [{:type "text" :text "I will help you."}]}}
          {message-id ::ai/message-id}
          (claude/persist-message! {::ai/node *test-node*
                                    ::ai/session-id session-id
                                    ::claude/sdk-message sdk-msg})]
      ;; Verify the message was stored
      (is (string? message-id))
      (is (clojure.string/starts-with? message-id "msg-"))
      ;; Retrieve messages for the session
      (let [messages (ai/get-messages {::ai/node *test-node*
                                        ::ai/session-id session-id})]
        (is (= 1 (count messages)))
        (let [msg (first messages)]
          (is (= :message (::ai/type msg)))
          (is (= session-id (::ai/session-id msg)))
          (is (= "assistant" (::ai/role msg)))
          (is (= "I will help you." (::ai/content msg)))
          (is (= "assistant" (::claude/message-type msg)))
          (is (= "msg-abc123" (::claude/uuid msg)))
          (is (temporal? (::ai/timestamp msg)))))))

  (testing "persist-message! stores tool calls"
    (let [{session-id ::ai/session-id}
          (ai/start-session! {::ai/node *test-node*})
          sdk-msg {:type "assistant"
                   :message {:role "assistant"
                             :content [{:type "text" :text "Reading file..."}
                                       {:type "tool_use"
                                        :id "tool-xyz"
                                        :name "Read"
                                        :input {:file_path "/tmp/test.txt"}}]}}
          _ (claude/persist-message! {::ai/node *test-node*
                                      ::ai/session-id session-id
                                      ::claude/sdk-message sdk-msg})
          messages (ai/get-messages {::ai/node *test-node*
                                     ::ai/session-id session-id})]
      (is (= 1 (count messages)))
      (let [msg (first messages)
            tool-calls (::claude/tool-calls msg)]
        (is (= 1 (count tool-calls)))
        (is (= "tool-xyz" (:id (first tool-calls))))
        (is (= "Read" (:name (first tool-calls))))))))

(deftest persist-message-result-test
  (testing "persist-message! handles result messages with stats"
    (let [{session-id ::ai/session-id}
          (ai/start-session! {::ai/node *test-node*})
          sdk-msg {:type "result"
                   :result "Task completed"
                   :subtype "success"
                   :num_turns 5
                   :total_cost_usd 0.05
                   :duration_ms 10000}
          _ (claude/persist-message! {::ai/node *test-node*
                                      ::ai/session-id session-id
                                      ::claude/sdk-message sdk-msg})
          messages (ai/get-messages {::ai/node *test-node*
                                     ::ai/session-id session-id})]
      (is (= 1 (count messages)))
      (let [msg (first messages)]
        (is (= "result" (::claude/message-type msg)))
        (is (= "Task completed" (::ai/content msg)))
        ;; Result maps to assistant role
        (is (= "assistant" (::ai/role msg)))))))

(deftest session-lifecycle-integration-test
  (testing "Full session lifecycle: start -> persist messages -> end -> query"
    ;; Start AI session
    (let [{session-id ::ai/session-id}
          (ai/start-session! {::ai/node *test-node*
                              ::ai/namespace 'seon.integration-test
                              ::ai/prompt "Test the integration"})]
      ;; Persist a user message
      (claude/persist-message! {::ai/node *test-node*
                                ::ai/session-id session-id
                                ::claude/sdk-message {:type "user"
                                                       :message {:role "user"
                                                                 :content [{:type "text"
                                                                            :text "What is 2+2?"}]}}})
      ;; Persist an assistant response
      (claude/persist-message! {::ai/node *test-node*
                                ::ai/session-id session-id
                                ::claude/sdk-message {:type "assistant"
                                                       :uuid "msg-resp"
                                                       :message {:role "assistant"
                                                                 :content [{:type "text"
                                                                            :text "2+2 equals 4."}]}}})
      ;; Persist a result message
      (claude/persist-message! {::ai/node *test-node*
                                ::ai/session-id session-id
                                ::claude/sdk-message {:type "result"
                                                       :result "Success"
                                                       :subtype "success"
                                                       :num_turns 2
                                                       :total_cost_usd 0.01}})
      ;; End session with stats
      (ai/end-session! {::ai/node *test-node*
                        ::ai/session-id session-id
                        ::ai/status :completed
                        ::ai/cost-usd 0.01})
      ;; Verify session state
      (let [session (ai/get-session {::ai/node *test-node*
                                     ::ai/session-id session-id})]
        (is (= :completed (::ai/status session)))
        (is (= 0.01 (::ai/cost-usd session)))
        (is (= "seon.integration-test" (::ai/namespace session))))
      ;; Verify messages
      (let [messages (ai/get-messages {::ai/node *test-node*
                                       ::ai/session-id session-id})]
        (is (= 3 (count messages)))
        (is (= ["user" "assistant" "assistant"]
               (mapv ::ai/role messages)))
        (is (= ["user" "assistant" "result"]
               (mapv ::claude/message-type messages)))))))

(deftest persistable-message-type-test
  (testing "Only user, assistant, system, result messages are persistable"
    ;; This tests the internal filtering logic used by launch-agent!
    ;; We use the private function via var deref
    (let [persistable? @#'claude/persistable-message-type?]
      ;; Set lookup returns the element if found (truthy), nil if not
      (is (persistable? "user"))
      (is (persistable? "assistant"))
      (is (persistable? "system"))
      (is (persistable? "result"))
      (is (nil? (persistable? "keep_alive")))
      (is (nil? (persistable? "parse_error"))))))

;;; ---------------------------------------------------------------------------
;;; Development Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.ai.claude-test)

  ;; Run specific test
  (clojure.test/test-var #'schema-registration-test)
  (clojure.test/test-var #'sdk-message-to-entity-basic-test)
  (clojure.test/test-var #'sdk-message-to-entity-tool-use-test)
  (clojure.test/test-var #'persist-message-test)
  (clojure.test/test-var #'session-lifecycle-integration-test)

  nil)
