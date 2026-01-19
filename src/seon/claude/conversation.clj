(ns seon.claude.conversation
  "DEPRECATED: Use seon.ai instead.

   This namespace is maintained for backwards compatibility only.
   All new code should use seon.ai for session/message persistence.

   Migration guide:
   - seon.claude.conversation/start-session!        -> seon.ai/start-session!
   - seon.claude.conversation/end-session!          -> seon.ai/end-session!
   - seon.claude.conversation/persist-message!      -> seon.ai.claude/persist-message!
   - seon.claude.conversation/get-session           -> seon.ai/get-session
   - seon.claude.conversation/get-session-messages  -> seon.ai/get-messages
   - seon.claude.conversation/list-recent-sessions  -> seon.ai/list-sessions

   The new namespaces provide:
   - Provider-agnostic base schemas in seon.ai
   - Claude-specific extensions in seon.ai.claude
   - Better schema composition and validation
   - Simpler API with namespaced keys

   ---

   LEGACY DOCS (for reference during migration):

   Conversation persistence for Claude Code agents.

   Stores all agent conversations in XTDB for:
   - Future model fine-tuning
   - Querying conversation history
   - Orchestrator/agent introspection

   Schema Design:
   - conversation/session  - Session metadata (start, agent, cost)
   - conversation/message  - Individual messages (type, content, timestamp)
   - conversation/turn     - Complete turns (prompt + response + tools)

   Usage:
     (require '[seon.claude.conversation :as conv])

     ;; Start a new session
     (conv/start-session! {::conv/node node
                           ::conv/session-id \"abc123\"
                           ::conv/namespace 'seon.trading})

     ;; Persist a message
     (conv/persist-message! {::conv/node node
                             ::conv/session-id \"abc123\"
                             ::conv/message msg})

     ;; Query conversations
     (conv/get-session-messages {::conv/node node
                                 ::conv/session-id \"abc123\"})"
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [seon.schema :as schema]
   [taoensso.timbre :as log]
   [xtdb.api :as xt])
  (:import
   [java.time Instant]
   [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

;; Session schemas
(schema/register! ::session-id
                  [:string {:min 1 :description "Claude session ID (UUID or 4-char hex)"}])

(schema/register! ::namespace
                  [:symbol {:description "Agent namespace symbol"}])

(schema/register! ::session-status
                  [:enum :active :completed :failed :interrupted])

(schema/register! ::session
                  [:map
                   [:xt/id ::session-id]
                   [::session-id ::session-id]
                   [::namespace {:optional true} ::namespace]
                   [::started-at inst?]
                   [::ended-at {:optional true} inst?]
                   [::status ::session-status]
                   [::total-cost-usd {:optional true} :double]
                   [::total-turns {:optional true} :int]
                   [::model {:optional true} :string]
                   [::prompt {:optional true} :string]])

;; Message schemas
(schema/register! ::message-type
                  [:enum "system" "assistant" "user" "result" "keep_alive" "parse_error"])

(schema/register! ::message-id
                  [:string {:description "Unique message ID: session-id/uuid"}])

(schema/register! ::message
                  [:map
                   [:xt/id ::message-id]
                   [::session-id ::session-id]
                   [::message-type ::message-type]
                   [::timestamp inst?]
                   [::uuid {:optional true} :string]
                   [::content {:optional true} :any]  ; Raw message content
                   [::text {:optional true} :string]  ; Extracted text (if any)
                   [::tool-calls {:optional true} [:vector :map]]  ; Tool use blocks
                   [::tool-results {:optional true} [:vector :map]]  ; Tool result blocks
                   [::cost-usd {:optional true} :double]
                   [::input-tokens {:optional true} :int]
                   [::output-tokens {:optional true} :int]])

;; Turn schemas (grouping prompt + response)
(schema/register! ::turn-id
                  [:string {:description "Turn ID: session-id/turn-N"}])

(schema/register! ::turn
                  [:map
                   [:xt/id ::turn-id]
                   [::session-id ::session-id]
                   [::turn-number :int]
                   [::prompt {:optional true} :string]
                   [::response {:optional true} :string]
                   [::tool-calls {:optional true} [:vector :map]]
                   [::started-at inst?]
                   [::ended-at {:optional true} inst?]
                   [::cost-usd {:optional true} :double]])

;; Request/Response schemas for public API
(schema/register! ::node
                  [:any {:description "XTDB node"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate XTDB node"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::start-session-request
                  [:map
                   [::node ::node]
                   [::session-id ::session-id]
                   [::namespace {:optional true} ::namespace]
                   [::model {:optional true} :string]
                   [::prompt {:optional true} :string]])

(schema/register! ::start-session-response
                  [:map
                   [::session-id ::session-id]
                   [::status ::session-status]])

(schema/register! ::persist-message-request
                  [:map
                   [::node ::node]
                   [::session-id ::session-id]
                   [::message :map]])

(schema/register! ::persist-message-response
                  [:map
                   [::message-id ::message-id]
                   [::persisted? :boolean]])

(schema/register! ::end-session-request
                  [:map
                   [::node ::node]
                   [::session-id ::session-id]
                   [::status ::session-status]
                   [::total-cost-usd {:optional true} :double]
                   [::total-turns {:optional true} :int]])

(schema/register! ::get-session-messages-request
                  [:map
                   [::node ::node]
                   [::session-id ::session-id]
                   [::message-type {:optional true} ::message-type]
                   [::limit {:optional true} :int]])

(schema/register! ::search-conversations-request
                  [:map
                   [::node ::node]
                   [::query {:optional true} :string]
                   [::namespace {:optional true} ::namespace]
                   [::since {:optional true} inst?]
                   [::limit {:optional true} :int]])

;; Additional request schemas for query functions
(schema/register! ::get-session-request
                  [:map
                   [::node ::node]
                   [::session-id ::session-id]])

(schema/register! ::get-session-summary-request
                  [:map
                   [::node ::node]
                   [::session-id ::session-id]])

(schema/register! ::list-recent-sessions-request
                  [:map
                   [::node ::node]
                   [::limit {:optional true} :int]])

(schema/register! ::persist-messages-request
                  [:map
                   [::node ::node]
                   [::session-id ::session-id]
                   [::messages [:vector :map]]])

(schema/register! ::persist-messages-response
                  [:map
                   [:count :int]])

(schema/register! ::create-message-persister-request
                  [:map
                   [::node ::node]
                   [::session-id ::session-id]])

;;; ---------------------------------------------------------------------------
;;; Private Helpers
;;; ---------------------------------------------------------------------------

(defn- now-instant
  "Get current instant."
  []
  (Instant/now))

(defn- extract-text-content
  "Extract text from message content blocks."
  [content]
  (cond
    (string? content) content
    (sequential? content)
    (->> content
         (filter #(= "text" (:type %)))
         (map :text)
         (str/join "\n"))
    :else nil))

(defn- extract-tool-calls
  "Extract tool_use blocks from message content."
  [content]
  (when (sequential? content)
    (->> content
         (filter #(= "tool_use" (:type %)))
         vec)))

(defn- extract-tool-results
  "Extract tool_result blocks from message content."
  [content]
  (when (sequential? content)
    (->> content
         (filter #(= "tool_result" (:type %)))
         vec)))

(defn- extract-usage
  "Extract token usage from message."
  [msg]
  (when-let [usage (or (:usage msg) (get-in msg [:message :usage]))]
    {:input-tokens (or (:input_tokens usage) 0)
     :output-tokens (or (:output_tokens usage) 0)}))

(defn- message-to-doc
  "Convert a Claude SDK message to an XTDB document."
  [session-id msg]
  (let [msg-type (:type msg)
        uuid (or (:uuid msg) (str (UUID/randomUUID)))
        msg-id (str session-id "/" uuid)
        content (get-in msg [:message :content])
        usage (extract-usage msg)]
    (cond-> {:xt/id msg-id
             ::session-id session-id
             ::message-type msg-type
             ::timestamp (now-instant)
             ::uuid uuid
             ::content msg}  ; Store full message for debugging/replay

      ;; Extract text for easy querying
      (extract-text-content content)
      (assoc ::text (extract-text-content content))

      ;; Extract tool calls
      (seq (extract-tool-calls content))
      (assoc ::tool-calls (extract-tool-calls content))

      ;; Extract tool results
      (seq (extract-tool-results content))
      (assoc ::tool-results (extract-tool-results content))

      ;; Add usage if available
      (:input-tokens usage)
      (assoc ::input-tokens (:input-tokens usage))

      (:output-tokens usage)
      (assoc ::output-tokens (:output-tokens usage))

      ;; Add cost from result messages
      (and (= "result" msg-type) (:total_cost_usd msg))
      (assoc ::cost-usd (:total_cost_usd msg)))))

;;; ---------------------------------------------------------------------------
;;; Public API - Session Management
;;; ---------------------------------------------------------------------------

(defn start-session!
  "Start a new conversation session.

   Request keys:
     ::node       - Required. XTDB node
     ::session-id - Required. Claude session ID
     ::namespace  - Optional. Agent namespace
     ::model      - Optional. Model name
     ::prompt     - Optional. Initial prompt

   Response keys:
     ::session-id - The session ID
     ::status     - Session status (:active)

   Example:
     (start-session! {::node node
                      ::session-id \"abc123\"
                      ::namespace 'seon.trading})"
  {:malli/schema [:=> [:cat ::start-session-request] ::start-session-response]}
  [{::keys [node session-id namespace model prompt]}]
  (let [session-doc {:xt/id session-id
                     ::session-id session-id
                     ::namespace namespace
                     ::started-at (now-instant)
                     ::status :active
                     ::model model
                     ::prompt prompt}]
    (xt/execute-tx node [[:put-docs :conversation_sessions session-doc]])
    (log/debug "Started conversation session" {:session-id session-id})
    {::session-id session-id
     ::status :active}))

(defn end-session!
  "End a conversation session with final stats.

   Request keys:
     ::node           - Required. XTDB node
     ::session-id     - Required. Session to end
     ::status         - Required. Final status
     ::total-cost-usd - Optional. Total cost
     ::total-turns    - Optional. Total turns

   Example:
     (end-session! {::node node
                    ::session-id \"abc123\"
                    ::status :completed
                    ::total-cost-usd 0.05
                    ::total-turns 3})"
  {:malli/schema [:=> [:cat ::end-session-request] ::start-session-response]}
  [{::keys [node session-id status total-cost-usd total-turns]}]
  ;; Get existing session and update
  (let [existing (first (xt/q node
                              ["SELECT * FROM conversation_sessions WHERE _id = ?" session-id]))
        updated (cond-> (or existing {:xt/id session-id ::session-id session-id})
                  true (assoc ::status status ::ended-at (now-instant))
                  total-cost-usd (assoc ::total-cost-usd total-cost-usd)
                  total-turns (assoc ::total-turns total-turns))]
    (xt/execute-tx node [[:put-docs :conversation_sessions updated]])
    (log/debug "Ended conversation session" {:session-id session-id :status status})
    {::session-id session-id
     ::status status}))

;;; ---------------------------------------------------------------------------
;;; Public API - Message Persistence
;;; ---------------------------------------------------------------------------

(defn persist-message!
  "Persist a Claude SDK message to XTDB.

   Request keys:
     ::node       - Required. XTDB node
     ::session-id - Required. Session this message belongs to
     ::message    - Required. The raw SDK message

   Response keys:
     ::message-id - Generated message ID
     ::persisted? - Whether persistence succeeded

   Example:
     (persist-message! {::node node
                        ::session-id \"abc123\"
                        ::message {:type \"assistant\" ...}})"
  {:malli/schema [:=> [:cat ::persist-message-request] ::persist-message-response]}
  [{::keys [node session-id message]}]
  (try
    (let [doc (message-to-doc session-id message)
          msg-id (:xt/id doc)]
      (xt/execute-tx node [[:put-docs :conversation_messages doc]])
      (log/trace "Persisted message" {:message-id msg-id :type (::message-type doc)})
      {::message-id msg-id
       ::persisted? true})
    (catch Exception e
      (log/warn e "Failed to persist message" {:session-id session-id})
      {::message-id nil
       ::persisted? false})))

(defn persist-messages!
  "Persist multiple messages in a single transaction.

   Request keys:
     ::node       - Required. XTDB node
     ::session-id - Required. Session ID
     ::messages   - Required. Vector of SDK messages

   Response keys:
     :count - Number of messages persisted

   Example:
     (persist-messages! {::node node
                         ::session-id \"abc123\"
                         ::messages [msg1 msg2 msg3]})"
  {:malli/schema [:=> [:cat ::persist-messages-request] ::persist-messages-response]}
  [{::keys [node session-id messages]}]
  (let [docs (mapv #(message-to-doc session-id %) messages)]
    (xt/execute-tx node (mapv #(vector :put-docs :conversation_messages %) docs))
    (log/debug "Persisted batch" {:session-id session-id :count (count docs)})
    {:count (count docs)}))

;;; ---------------------------------------------------------------------------
;;; Public API - Queries
;;; ---------------------------------------------------------------------------

(defn get-session
  "Get session metadata.

   Request keys:
     ::node       - Required. XTDB node
     ::session-id - Required. Session to retrieve

   Returns session document or nil.

   Example:
     (get-session {::node node ::session-id \"abc123\"})"
  {:malli/schema [:=> [:cat ::get-session-request] [:maybe :map]]}
  [{::keys [node session-id]}]
  (first (xt/q node
               ["SELECT * FROM conversation_sessions WHERE _id = ?" session-id])))

(defn get-session-messages
  "Get all messages for a session.

   Request keys:
     ::node         - Required. XTDB node
     ::session-id   - Required. Session to query
     ::message-type - Optional. Filter by message type
     ::limit        - Optional. Max messages to return (default: all)

   Returns vector of message documents ordered by timestamp."
  {:malli/schema [:=> [:cat ::get-session-messages-request] [:vector :map]]}
  [{::keys [node session-id message-type limit]}]
  (let [base-sql "SELECT * FROM conversation_messages WHERE seon$claude$conversation$session_id = ?"
        sql (cond-> base-sql
              message-type (str " AND seon$claude$conversation$message_type = '" message-type "'")
              true (str " ORDER BY seon$claude$conversation$timestamp")
              limit (str " LIMIT " limit))]
    (vec (xt/q node [sql session-id]))))

(defn get-session-summary
  "Get a summary of a session including stats.

   Request keys:
     ::node       - Required. XTDB node
     ::session-id - Required. Session to summarize

   Returns map with session metadata and computed stats.

   Example:
     (get-session-summary {::node node ::session-id \"abc123\"})"
  {:malli/schema [:=> [:cat ::get-session-summary-request] [:maybe :map]]}
  [{::keys [node session-id]}]
  (let [session (get-session {::node node ::session-id session-id})
        messages (get-session-messages {::node node ::session-id session-id})
        message-counts (->> messages
                            (group-by ::message-type)
                            (map (fn [[k v]] [k (count v)]))
                            (into {}))
        total-tokens (->> messages
                          (map #(+ (or (::input-tokens %) 0)
                                   (or (::output-tokens %) 0)))
                          (reduce + 0))
        tool-calls (->> messages
                        (mapcat ::tool-calls)
                        (map :name)
                        frequencies)]
    (merge session
           {:message-counts message-counts
            :total-messages (count messages)
            :total-tokens total-tokens
            :tool-usage tool-calls})))

(defn search-conversations
  "Search across conversations.

   Request keys:
     ::node      - Required. XTDB node
     ::query     - Optional. Text to search for in messages
     ::namespace - Optional. Filter by agent namespace
     ::since     - Optional. Only sessions started after this time
     ::limit     - Optional. Max results (default: 100)

   Returns vector of session summaries."
  {:malli/schema [:=> [:cat ::search-conversations-request] [:vector :map]]}
  [{::keys [node query namespace since limit]}]
  (let [limit (or limit 100)
        base-sql "SELECT * FROM conversation_sessions"
        conditions []
        conditions (cond-> conditions
                     namespace
                     (conj (str "seon$claude$conversation$namespace = '" namespace "'"))

                     since
                     (conj "seon$claude$conversation$started_at > ?"))
        sql (cond-> base-sql
              (seq conditions)
              (str " WHERE " (str/join " AND " conditions))
              true
              (str " ORDER BY seon$claude$conversation$started_at DESC")
              limit
              (str " LIMIT " limit))
        params (cond-> []
                 since (conj since))]
    (vec (xt/q node (into [sql] params)))))

(defn list-recent-sessions
  "List recent conversation sessions.

   Request keys:
     ::node  - Required. XTDB node
     ::limit - Optional. Max sessions (default: 20)

   Returns vector of recent sessions.

   Example:
     (list-recent-sessions {::node node ::limit 10})"
  {:malli/schema [:=> [:cat ::list-recent-sessions-request] [:vector :map]]}
  [{::keys [node limit]}]
  (let [limit (or limit 20)]
    (vec (xt/q node
               [(str "SELECT * FROM conversation_sessions
                      ORDER BY seon$claude$conversation$started_at DESC
                      LIMIT " limit)]))))

;;; ---------------------------------------------------------------------------
;;; Integration Helpers
;;; ---------------------------------------------------------------------------

(defn create-message-persister
  "Create a function that persists messages for a session.

   Request keys:
     ::node       - Required. XTDB node
     ::session-id - Required. Session ID for messages

   Returns a function (fn [message] ...) that can be used as a callback
   when reading messages from the SDK.

   Example:
     (let [persist! (create-message-persister {::node node ::session-id \"abc123\"})]
       (go-loop []
         (when-let [msg (<! messages-ch)]
           (persist! msg)
           (recur))))"
  {:malli/schema [:=> [:cat ::create-message-persister-request] fn?]}
  [{::keys [node session-id]}]
  (fn [message]
    (persist-message! {::node node
                       ::session-id session-id
                       ::message message})))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  (require '[user :refer [xtdb-node]])

  ;; Start a session
  (start-session! {::node (xtdb-node)
                   ::session-id "test-session-1"
                   ::namespace 'seon.test})

  ;; Persist a message
  (persist-message! {::node (xtdb-node)
                     ::session-id "test-session-1"
                     ::message {:type "assistant"
                                :message {:content [{:type "text" :text "Hello!"}]}}})

  ;; Get session messages
  (get-session-messages {::node (xtdb-node)
                         ::session-id "test-session-1"})

  ;; Get session summary
  (get-session-summary {::node (xtdb-node)
                        ::session-id "test-session-1"})

  ;; List recent sessions
  (list-recent-sessions {::node (xtdb-node)})

  ;; End session
  (end-session! {::node (xtdb-node)
                 ::session-id "test-session-1"
                 ::status :completed
                 ::total-cost-usd 0.05})

  nil)
