(ns seon.ai-test
  "Tests for seon.ai base AI namespace.

   Tests cover:
   1. Schema registration - schemas are in global registry and valid
   2. Schema generation - can generate valid sample data
   3. Session lifecycle - start, add messages, end, retrieve
   4. Query functions - get-session, get-messages, list-sessions"
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures compose-fixtures]]
   [malli.core :as m]
   [malli.generator :as mg]
   [seon.ai :as ai]
   [seon.schema :as schema]
   [seon.test-utils :refer [with-test-node with-test-datalevin *test-node*]])
  (:import [java.time Instant ZonedDateTime]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(use-fixtures :each (compose-fixtures with-test-node with-test-datalevin))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn temporal?
  "Check if value is a temporal type (Instant, ZonedDateTime, or java.util.Date).
   Datalevin returns java.util.Date for :db.type/instant attrs."
  [v]
  (or (instance? Instant v)
      (instance? ZonedDateTime v)
      (instance? java.util.Date v)))

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
      (is (contains? ai-schemas ::ai/prompt))))

  (testing "Tool call schemas are registered"
    (let [ai-schemas (schema/schemas-in-namespace "seon.ai")]
      (is (contains? ai-schemas ::ai/provider))
      (is (contains? ai-schemas ::ai/tool-call))
      (is (contains? ai-schemas ::ai/tool-calls))
      (is (contains? ai-schemas ::ai/tool-result))
      (is (contains? ai-schemas ::ai/tool-results)))))

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
;;; Tool Call Schema Tests
;;; ---------------------------------------------------------------------------

(deftest provider-schema-test
  (testing "Can generate ::ai/provider"
    (let [generated (mg/generate ::ai/provider)]
      (is (#{:claude :gemini :openai} generated))))

  (testing "Can generate multiple valid providers"
    (let [samples (mg/sample ::ai/provider {:size 10})]
      (is (= 10 (count samples)))
      (doseq [provider samples]
        (is (m/validate ::ai/provider provider))))))

(deftest tool-call-schema-test
  (testing "Can generate ::ai/tool-call"
    (let [generated (mg/generate ::ai/tool-call)]
      (is (map? generated))
      (is (contains? generated :id))
      (is (contains? generated :name))
      (is (string? (:id generated)))
      (is (string? (:name generated)))))

  (testing "tool-call validates correctly"
    (is (m/validate ::ai/tool-call {:id "tool-123" :name "read_file"}))
    (is (m/validate ::ai/tool-call {:id "tool-456" :name "write_file" :input {:path "/tmp/test.txt"}}))
    (is (not (m/validate ::ai/tool-call {:name "missing-id"})))
    (is (not (m/validate ::ai/tool-call {:id "missing-name"})))))

(deftest tool-calls-schema-test
  (testing "Can generate ::ai/tool-calls"
    (let [generated (mg/generate ::ai/tool-calls)]
      (is (vector? generated))
      (doseq [tc generated]
        (is (m/validate ::ai/tool-call tc)))))

  (testing "tool-calls validates correctly"
    (is (m/validate ::ai/tool-calls []))
    (is (m/validate ::ai/tool-calls [{:id "t1" :name "read"}]))
    (is (m/validate ::ai/tool-calls [{:id "t1" :name "read"} {:id "t2" :name "write" :input {:data "x"}}]))))

(deftest tool-result-schema-test
  (testing "Can generate ::ai/tool-result"
    (let [generated (mg/generate ::ai/tool-result)]
      (is (map? generated))
      (is (contains? generated :tool-use-id))
      (is (string? (:tool-use-id generated)))))

  (testing "tool-result validates correctly"
    (is (m/validate ::ai/tool-result {:tool-use-id "tool-123"}))
    (is (m/validate ::ai/tool-result {:tool-use-id "tool-456" :content "Success!"}))
    (is (m/validate ::ai/tool-result {:tool-use-id "tool-789" :content {:data "structured"} :is-error false}))
    (is (m/validate ::ai/tool-result {:tool-use-id "tool-err" :is-error true :content "File not found"}))
    (is (not (m/validate ::ai/tool-result {:content "missing tool-use-id"})))))

(deftest tool-results-schema-test
  (testing "Can generate ::ai/tool-results"
    (let [generated (mg/generate ::ai/tool-results)]
      (is (vector? generated))
      (doseq [tr generated]
        (is (m/validate ::ai/tool-result tr)))))

  (testing "tool-results validates correctly"
    (is (m/validate ::ai/tool-results []))
    (is (m/validate ::ai/tool-results [{:tool-use-id "t1"}]))
    (is (m/validate ::ai/tool-results [{:tool-use-id "t1" :content "ok"} {:tool-use-id "t2" :is-error true}]))))

(deftest message-entity-with-tools-test
  (testing "message-entity validates with tool-calls"
    (let [msg {:seon/id "msg-123"
               ::ai/type :message
               ::ai/session-id "ses-456"
               ::ai/role "assistant"
               ::ai/content "I'll read that file for you."
               ::ai/timestamp (Instant/now)
               ::ai/tool-calls [{:id "tc-1" :name "read_file" :input {:path "/tmp/test.txt"}}]
               ::ai/provider :claude}]
      (is (m/validate ::ai/message-entity msg))))

  (testing "message-entity validates with tool-results"
    (let [msg {:seon/id "msg-789"
               ::ai/type :message
               ::ai/session-id "ses-456"
               ::ai/role "user"
               ::ai/content "Tool results"
               ::ai/timestamp (Instant/now)
               ::ai/tool-results [{:tool-use-id "tc-1" :content "file contents here"}]
               ::ai/provider :claude}]
      (is (m/validate ::ai/message-entity msg))))

  (testing "message-entity validates without tool fields (backwards compatible)"
    (let [msg {:seon/id "msg-simple"
               ::ai/type :message
               ::ai/session-id "ses-123"
               ::ai/role "user"
               ::ai/content "Hello!"
               ::ai/timestamp (Instant/now)}]
      (is (m/validate ::ai/message-entity msg)))))

;;; ---------------------------------------------------------------------------
;;; Session Lifecycle Tests
;;; ---------------------------------------------------------------------------

(deftest start-session-test
  (testing "start-session! creates a session and returns session-id"
    (let [result (ai/start-session! {::ai/node *test-node*})]
      (is (map? result))
      (is (contains? result ::ai/session-id))
      (is (string? (::ai/session-id result)))
      (is (str/starts-with? (::ai/session-id result) "ses-"))))

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
          first-session-id (:seon/id (first active-sessions))]
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

;;; ---------------------------------------------------------------------------
;;; Session Stats Tests
;;; ---------------------------------------------------------------------------

(deftest session-stats-empty-db-test
  (testing "session-stats returns zeros for empty database"
    (let [stats (ai/session-stats {::ai/node *test-node*})]
      (is (map? stats))
      (is (= 0.0 (::ai/total-cost-usd stats)))
      (is (= 0 (::ai/total-sessions stats)))
      (is (= 0 (::ai/total-messages stats)))
      (is (= {:input 0 :output 0 :cache-read 0 :cache-creation 0}
             (::ai/tokens stats)))
      (is (= 0.0 (::ai/cache-hit-rate stats))))))

(deftest session-stats-with-data-test
  (testing "session-stats aggregates session and message data"
    ;; Create sessions with cost
    (let [{session1 ::ai/session-id} (ai/start-session! {::ai/node *test-node*})
          {session2 ::ai/session-id} (ai/start-session! {::ai/node *test-node*})]
      ;; End sessions with costs
      (ai/end-session! {::ai/node *test-node*
                        ::ai/session-id session1
                        ::ai/cost-usd 1.50})
      (ai/end-session! {::ai/node *test-node*
                        ::ai/session-id session2
                        ::ai/cost-usd 2.25})
      ;; Add messages with token counts
      (ai/add-message! {::ai/node *test-node*
                        ::ai/session-id session1
                        ::ai/role "assistant"
                        ::ai/content "Response 1"
                        ::ai/input-tokens 100
                        ::ai/output-tokens 50})
      (ai/add-message! {::ai/node *test-node*
                        ::ai/session-id session2
                        ::ai/role "assistant"
                        ::ai/content "Response 2"
                        ::ai/input-tokens 200
                        ::ai/output-tokens 75})
      ;; Get stats
      (let [stats (ai/session-stats {::ai/node *test-node*})]
        (is (= 3.75 (::ai/total-cost-usd stats)))
        (is (= 2 (::ai/total-sessions stats)))
        (is (= 2 (::ai/total-messages stats)))
        (is (= 300 (:input (::ai/tokens stats))))
        (is (= 125 (:output (::ai/tokens stats))))))))

(deftest session-stats-cache-hit-rate-test
  (testing "cache-hit-rate is calculated correctly"
    ;; With cache-read = 800 and input = 200, rate should be 0.8
    ;; But we can't directly add Claude cache tokens via add-message!
    ;; So we test the edge case: no cache tokens means 0.0 rate
    (let [{session-id ::ai/session-id} (ai/start-session! {::ai/node *test-node*})]
      (ai/add-message! {::ai/node *test-node*
                        ::ai/session-id session-id
                        ::ai/role "assistant"
                        ::ai/content "Test"
                        ::ai/input-tokens 100
                        ::ai/output-tokens 50})
      (let [stats (ai/session-stats {::ai/node *test-node*})]
        ;; With input tokens but no cache-read, rate should be 0.0
        (is (= 0.0 (::ai/cache-hit-rate stats)))))))

(deftest structured-content-test
  (testing "add-message! handles structured content (map)"
    (let [start-result (ai/start-session! {::ai/node *test-node*})
          session-id (::ai/session-id start-result)
          ;; Use kebab-case keys since Datalevin normalizes keys
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

;;; ---------------------------------------------------------------------------
;;; Initial Context Schema Tests
;;; ---------------------------------------------------------------------------

(deftest file-context-schema-test
  (testing "::ai/file-context generates and validates correctly"
    (let [fc {::ai/path "test.clj"
              ::ai/content "(ns test)"
              ::ai/language "clojure"
              ::ai/byte-count 10
              ::ai/read-success true}]
      (is (m/validate ::ai/file-context fc))))

  (testing "::ai/file-context validates with error"
    (let [fc {::ai/path "missing.clj"
              ::ai/content ""
              ::ai/language ""
              ::ai/byte-count 0
              ::ai/read-success false
              ::ai/error "File not found"}]
      (is (m/validate ::ai/file-context fc))))

  (testing "::ai/file-context rejects invalid data"
    (is (not (m/validate ::ai/file-context {:path "test.clj"})))  ; Missing required keys
    (is (not (m/validate ::ai/file-context {::ai/path 123})))))  ; Wrong type

(deftest files-context-schema-test
  (testing "::ai/files-context validates vector of file contexts"
    (let [fcs [{::ai/path "a.clj"
                ::ai/content "(ns a)"
                ::ai/language "clojure"
                ::ai/byte-count 6
                ::ai/read-success true}
               {::ai/path "b.md"
                ::ai/content "# Title"
                ::ai/language "markdown"
                ::ai/byte-count 7
                ::ai/read-success true}]]
      (is (m/validate ::ai/files-context fcs))))

  (testing "::ai/files-context validates empty vector"
    (is (m/validate ::ai/files-context []))))

(deftest initial-context-schema-test
  (testing "::ai/initial-context validates with all fields"
    (let [ctx {::ai/task-prompt "Implement the feature"
               ::ai/files-context [{::ai/path "prd.md"
                                    ::ai/content "# PRD"
                                    ::ai/language "markdown"
                                    ::ai/byte-count 5
                                    ::ai/read-success true}]
               ::ai/agent-instructions "You are a helpful agent..."
               ::ai/agent-instructions-path "AGENT.md"}]
      (is (m/validate ::ai/initial-context ctx))))

  (testing "::ai/initial-context validates with only required field"
    (let [ctx {::ai/task-prompt "Do the thing"}]
      (is (m/validate ::ai/initial-context ctx))))

  (testing "::ai/initial-context rejects missing task-prompt"
    (is (not (m/validate ::ai/initial-context {})))))

;;; ---------------------------------------------------------------------------
;;; Initial Context Persistence Tests
;;; ---------------------------------------------------------------------------

(deftest start-session-with-initial-context-test
  (testing "start-session! stores and retrieves initial context"
    (let [initial-ctx {::ai/task-prompt "Test task for initial context"
                       ::ai/files-context [{::ai/path "test.clj"
                                            ::ai/content "(ns test)\n(defn hello [])"
                                            ::ai/language "clojure"
                                            ::ai/byte-count 30
                                            ::ai/read-success true}]
                       ::ai/agent-instructions "You are a test agent."
                       ::ai/agent-instructions-path "AGENT.md"}
          {sid ::ai/session-id} (ai/start-session! {::ai/node *test-node*
                                                    ::ai/namespace 'seon.test
                                                    ::ai/prompt "Test prompt"
                                                    ::ai/initial-context initial-ctx})
          session (ai/get-session {::ai/node *test-node* ::ai/session-id sid})]
      (is (some? session))
      ;; Datalevin adds :db/id to stored entities — compare only expected keys

      (let [stored (::ai/initial-context session)
            strip-db-id (fn [m] (dissoc m :db/id))
            stored-clean (-> (strip-db-id stored)
                             (update ::ai/files-context #(mapv strip-db-id %)))]
        (is (= initial-ctx stored-clean)))))

  (testing "start-session! works without initial context (backwards compatible)"
    (let [{sid ::ai/session-id} (ai/start-session! {::ai/node *test-node*
                                                    ::ai/namespace 'seon.test
                                                    ::ai/prompt "Test prompt"})
          session (ai/get-session {::ai/node *test-node* ::ai/session-id sid})]
      (is (some? session))
      (is (nil? (::ai/initial-context session))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.ai-test)

  ;; Run specific test
  (clojure.test/test-var #'schema-registration-test)
  (clojure.test/test-var #'start-session-test)
  (clojure.test/test-var #'session-round-trip-test))
