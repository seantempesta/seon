(ns seon.ai-test
  "Tests for seon.ai base AI namespace.

   Tests cover:
   1. Schema registration - schemas are in global registry and valid
   2. Schema generation - can generate valid sample data
   3. Session lifecycle - start, add messages, end, retrieve
   4. Query functions - get-session, get-messages, list-sessions"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [malli.core :as m]
   [malli.generator :as mg]
   [seon.ai :as ai]
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
  (testing "AI schemas are registered in global registry"
    (let [ai-schemas (schema/schemas-in-namespace "seon.ai")]
      (is (pos? (count ai-schemas)) "Should have registered schemas")
      (is (contains? ai-schemas ::ai/type))
      (is (contains? ai-schemas ::ai/role))
      (is (contains? ai-schemas ::ai/content))
      (is (contains? ai-schemas ::ai/status))
      (is (contains? ai-schemas ::ai/session-id))
      (is (contains? ai-schemas ::ai/message-id))
      (is (contains? ai-schemas ::ai/timestamp))
      (is (contains? ai-schemas ::ai/input-tokens))
      (is (contains? ai-schemas ::ai/output-tokens))
      (is (contains? ai-schemas ::ai/cost-usd))
      (is (contains? ai-schemas ::ai/namespace))
      (is (contains? ai-schemas ::ai/prompt)))))

(deftest schema-validity-test
  (testing "All registered AI schemas are valid Malli schemas"
    (doseq [[k _] (schema/schemas-in-namespace "seon.ai")]
      (is (m/schema? (m/schema k))
          (str "Schema " k " should be a valid Malli schema")))))

;;; ---------------------------------------------------------------------------
;;; Schema Generation Tests
;;; ---------------------------------------------------------------------------

(deftest schema-generation-test
  (testing "Can generate ::ai/type"
    (let [generated (mg/generate ::ai/type)]
      (is (#{:session :message :tool-call} generated))))

  (testing "Can generate ::ai/role"
    (let [generated (mg/generate ::ai/role)]
      (is (#{"user" "assistant" "system"} generated))))

  (testing "Can generate ::ai/content as string"
    (let [generated (mg/generate ::ai/content)]
      (is (or (string? generated) (map? generated)))))

  (testing "Can generate ::ai/status"
    (let [generated (mg/generate ::ai/status)]
      (is (#{:active :completed :failed :interrupted} generated))))

  (testing "Can generate ::ai/session-id"
    (let [generated (mg/generate ::ai/session-id)]
      (is (string? generated))
      (is (pos? (count generated)))))

  (testing "Can generate ::ai/input-tokens"
    (let [generated (mg/generate ::ai/input-tokens)]
      (is (int? generated))
      (is (>= generated 0))))

  (testing "Can generate ::ai/cost-usd"
    (let [generated (mg/generate ::ai/cost-usd)]
      (is (double? generated))
      (is (>= generated 0.0)))))

(deftest complex-schema-generation-test
  (testing "Can generate multiple valid roles"
    (let [samples (mg/sample ::ai/role {:size 10})]
      (is (= 10 (count samples)))
      (doseq [role samples]
        (is (m/validate ::ai/role role)))))

  (testing "Can generate multiple valid statuses"
    (let [samples (mg/sample ::ai/status {:size 10})]
      (is (= 10 (count samples)))
      (doseq [status samples]
        (is (m/validate ::ai/status status))))))

;;; ---------------------------------------------------------------------------
;;; Session Lifecycle Tests
;;; ---------------------------------------------------------------------------

(deftest start-session-test
  (testing "start-session! creates a session and returns session-id"
    (let [result (ai/start-session! {::ai/node *test-node*})]
      (is (map? result))
      (is (contains? result ::ai/session-id))
      (is (string? (::ai/session-id result)))
      (is (clojure.string/starts-with? (::ai/session-id result) "ses-"))))

  (testing "start-session! with namespace and prompt"
    (let [result (ai/start-session! {::ai/node *test-node*
                                     ::ai/namespace 'seon.trading
                                     ::ai/prompt "Analyze options data"})]
      (is (contains? result ::ai/session-id))
      ;; Verify the session was stored with the attributes
      (let [session (ai/get-session {::ai/node *test-node*
                                     ::ai/session-id (::ai/session-id result)})]
        (is (some? session))
        (is (= :session (::ai/type session)))
        (is (= :active (::ai/status session)))
        ;; Namespace is stored as string
        (is (= "seon.trading" (::ai/namespace session)))
        (is (= "Analyze options data" (::ai/prompt session)))
        (is (temporal? (::ai/started-at session)))))))

(deftest end-session-test
  (testing "end-session! closes a session with status"
    (let [start-result (ai/start-session! {::ai/node *test-node*})
          session-id (::ai/session-id start-result)
          end-result (ai/end-session! {::ai/node *test-node*
                                       ::ai/session-id session-id
                                       ::ai/status :completed
                                       ::ai/input-tokens 1500
                                       ::ai/output-tokens 800
                                       ::ai/cost-usd 0.05})]
      (is (= session-id (::ai/session-id end-result)))
      (is (= :completed (::ai/status end-result)))
      ;; Verify the session was updated
      (let [session (ai/get-session {::ai/node *test-node*
                                     ::ai/session-id session-id})]
        (is (= :completed (::ai/status session)))
        (is (= 1500 (::ai/input-tokens session)))
        (is (= 800 (::ai/output-tokens session)))
        (is (= 0.05 (::ai/cost-usd session)))
        (is (temporal? (::ai/ended-at session))))))

  (testing "end-session! defaults to :completed status"
    (let [start-result (ai/start-session! {::ai/node *test-node*})
          session-id (::ai/session-id start-result)
          end-result (ai/end-session! {::ai/node *test-node*
                                       ::ai/session-id session-id})]
      (is (= :completed (::ai/status end-result))))))

;;; ---------------------------------------------------------------------------
;;; Message Tests
;;; ---------------------------------------------------------------------------

(deftest add-message-test
  (testing "add-message! creates a message and returns message-id"
    (let [start-result (ai/start-session! {::ai/node *test-node*})
          session-id (::ai/session-id start-result)
          msg-result (ai/add-message! {::ai/node *test-node*
                                       ::ai/session-id session-id
                                       ::ai/role "user"
                                       ::ai/content "Hello, AI!"})]
      (is (map? msg-result))
      (is (contains? msg-result ::ai/message-id))
      (is (string? (::ai/message-id msg-result)))
      (is (clojure.string/starts-with? (::ai/message-id msg-result) "msg-"))))

  (testing "add-message! with token counts"
    (let [start-result (ai/start-session! {::ai/node *test-node*})
          session-id (::ai/session-id start-result)
          msg-result (ai/add-message! {::ai/node *test-node*
                                       ::ai/session-id session-id
                                       ::ai/role "assistant"
                                       ::ai/content "Hello! How can I help?"
                                       ::ai/input-tokens 10
                                       ::ai/output-tokens 5})]
      (is (contains? msg-result ::ai/message-id))
      ;; Verify messages can be retrieved
      (let [messages (ai/get-messages {::ai/node *test-node*
                                       ::ai/session-id session-id})]
        (is (= 1 (count messages)))
        (let [msg (first messages)]
          (is (= :message (::ai/type msg)))
          (is (= session-id (::ai/session-id msg)))
          (is (= "assistant" (::ai/role msg)))
          (is (= "Hello! How can I help?" (::ai/content msg)))
          (is (= 10 (::ai/input-tokens msg)))
          (is (= 5 (::ai/output-tokens msg)))
          (is (temporal? (::ai/timestamp msg))))))))

(deftest get-messages-ordering-test
  (testing "get-messages returns messages in timestamp order"
    (let [start-result (ai/start-session! {::ai/node *test-node*})
          session-id (::ai/session-id start-result)
          _ (ai/add-message! {::ai/node *test-node*
                             ::ai/session-id session-id
                             ::ai/role "user"
                             ::ai/content "First message"})
          _ (Thread/sleep 10) ; Ensure different timestamps
          _ (ai/add-message! {::ai/node *test-node*
                             ::ai/session-id session-id
                             ::ai/role "assistant"
                             ::ai/content "Second message"})
          _ (Thread/sleep 10)
          _ (ai/add-message! {::ai/node *test-node*
                             ::ai/session-id session-id
                             ::ai/role "user"
                             ::ai/content "Third message"})
          messages (ai/get-messages {::ai/node *test-node*
                                     ::ai/session-id session-id})]
      (is (= 3 (count messages)))
      (is (= ["First message" "Second message" "Third message"]
             (mapv ::ai/content messages))))))

;;; ---------------------------------------------------------------------------
;;; List Sessions Tests
;;; ---------------------------------------------------------------------------

(deftest list-sessions-test
  (testing "list-sessions returns sessions"
    ;; Create a few sessions
    (ai/start-session! {::ai/node *test-node* ::ai/namespace 'seon.trading})
    (ai/start-session! {::ai/node *test-node* ::ai/namespace 'seon.health})
    (ai/start-session! {::ai/node *test-node* ::ai/namespace 'seon.trading})

    (let [sessions (ai/list-sessions {::ai/node *test-node*})]
      (is (= 3 (count sessions)))
      (is (every? #(= :session (::ai/type %)) sessions))))

  (testing "list-sessions respects limit"
    (let [sessions (ai/list-sessions {::ai/node *test-node* ::ai/limit 2})]
      (is (= 2 (count sessions)))))

  (testing "list-sessions filters by namespace"
    (let [sessions (ai/list-sessions {::ai/node *test-node*
                                      ::ai/namespace 'seon.trading})]
      (is (= 2 (count sessions)))
      ;; Namespace is stored as string
      (is (every? #(= "seon.trading" (::ai/namespace %)) sessions))))

  (testing "list-sessions filters by status"
    ;; End one session
    (let [active-sessions (ai/list-sessions {::ai/node *test-node*
                                             ::ai/status :active})
          first-session-id (:xt/id (first active-sessions))]
      (ai/end-session! {::ai/node *test-node*
                        ::ai/session-id first-session-id
                        ::ai/status :completed})

      (let [completed (ai/list-sessions {::ai/node *test-node*
                                         ::ai/status :completed})
            still-active (ai/list-sessions {::ai/node *test-node*
                                            ::ai/status :active})]
        (is (= 1 (count completed)))
        (is (= 2 (count still-active)))))))

;;; ---------------------------------------------------------------------------
;;; Round-Trip Integration Test
;;; ---------------------------------------------------------------------------

(deftest session-round-trip-test
  (testing "Complete session lifecycle: start -> messages -> end -> retrieve"
    ;; Start session
    (let [start-result (ai/start-session! {::ai/node *test-node*
                                           ::ai/namespace 'seon.ai-test
                                           ::ai/prompt "Test prompt"})
          session-id (::ai/session-id start-result)]

      ;; Add messages
      (ai/add-message! {::ai/node *test-node*
                        ::ai/session-id session-id
                        ::ai/role "user"
                        ::ai/content "What is 2+2?"})
      (ai/add-message! {::ai/node *test-node*
                        ::ai/session-id session-id
                        ::ai/role "assistant"
                        ::ai/content "2+2 equals 4."
                        ::ai/output-tokens 10})

      ;; End session
      (ai/end-session! {::ai/node *test-node*
                        ::ai/session-id session-id
                        ::ai/status :completed
                        ::ai/input-tokens 100
                        ::ai/output-tokens 50
                        ::ai/cost-usd 0.001})

      ;; Verify session
      (let [session (ai/get-session {::ai/node *test-node*
                                     ::ai/session-id session-id})]
        (is (= :session (::ai/type session)))
        (is (= :completed (::ai/status session)))
        ;; Namespace is stored as string
        (is (= "seon.ai-test" (::ai/namespace session)))
        (is (= "Test prompt" (::ai/prompt session)))
        (is (= 100 (::ai/input-tokens session)))
        (is (= 50 (::ai/output-tokens session)))
        (is (= 0.001 (::ai/cost-usd session))))

      ;; Verify messages
      (let [messages (ai/get-messages {::ai/node *test-node*
                                       ::ai/session-id session-id})]
        (is (= 2 (count messages)))
        (is (= "user" (::ai/role (first messages))))
        (is (= "assistant" (::ai/role (second messages))))))))

;;; ---------------------------------------------------------------------------
;;; Edge Cases
;;; ---------------------------------------------------------------------------

(deftest get-session-not-found-test
  (testing "get-session returns nil for non-existent session"
    (let [result (ai/get-session {::ai/node *test-node*
                                  ::ai/session-id "ses-nonexistent"})]
      (is (nil? result)))))

(deftest get-messages-empty-test
  (testing "get-messages returns empty vector for session with no messages"
    (let [start-result (ai/start-session! {::ai/node *test-node*})
          session-id (::ai/session-id start-result)
          messages (ai/get-messages {::ai/node *test-node*
                                     ::ai/session-id session-id})]
      (is (vector? messages))
      (is (empty? messages)))))

(deftest structured-content-test
  (testing "add-message! handles structured content (map)"
    (let [start-result (ai/start-session! {::ai/node *test-node*})
          session-id (::ai/session-id start-result)
          ;; Use kebab-case keys since XTDB normalizes keys
          structured-content {:type "tool_result"
                             :tool-use-id "tool-123"
                             :content "Result data"}
          _ (ai/add-message! {::ai/node *test-node*
                              ::ai/session-id session-id
                              ::ai/role "user"
                              ::ai/content structured-content})
          messages (ai/get-messages {::ai/node *test-node*
                                     ::ai/session-id session-id})]
      (is (= 1 (count messages)))
      (is (= structured-content (::ai/content (first messages)))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.ai-test)

  ;; Run specific test
  (clojure.test/test-var #'schema-registration-test)
  (clojure.test/test-var #'start-session-test)
  (clojure.test/test-var #'session-round-trip-test))
