(ns seon.ai
  "Base AI namespace defining common schemas and entity persistence.

   This namespace provides provider-agnostic schemas and functions for AI sessions
   and messages. Provider-specific extensions (like seon.ai.claude or seon.ai.gemini)
   can extend these base schemas with their own attributes.

   ## Schemas

   - `::type` - Entity type: :session, :message, :tool-call
   - `::role` - Message role: \"user\", \"assistant\", \"system\"
   - `::content` - Message content (string or structured)
   - `::timestamp` - When the event occurred
   - `::session-id` - Reference to parent session
   - `::status` - Status: :active, :completed, :failed, :interrupted
   - `::input-tokens`, `::output-tokens`, `::cost-usd` - Usage tracking
   - `::namespace` - Clojure namespace context
   - `::prompt` - Initial prompt that started a session

   ## Functions

   All functions use map-in, map-out with namespaced keys:

   - `(start-session! {::node db ::namespace 'seon.trading ::prompt \"...\"})`
   - `(end-session! {::node db ::session-id \"...\" ::status :completed})`
   - `(add-message! {::node db ::session-id \"...\" ::role \"assistant\" ::content \"...\"})`
   - `(get-session {::node db ::session-id \"...\"})`
   - `(get-messages {::node db ::session-id \"...\"})`
   - `(list-sessions {::node db ::limit 20})`

   Example:

     (require '[seon.ai :as ai])
     (require '[seon.db.node :as db])

     ;; Start a session
     (ai/start-session! {::ai/node my-xtdb-node
                         ::ai/namespace 'seon.trading
                         ::ai/prompt \"Analyze options data\"})

     ;; Add a message
     (ai/add-message! {::ai/node my-xtdb-node
                       ::ai/session-id \"ses-abc123\"
                       ::ai/role \"assistant\"
                       ::ai/content \"I'll analyze the data...\"})"
  (:require [seon.db.node :as db]
            [seon.schema :as schema])
  (:import [java.time Instant]
           [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

;; NOTE: Functions that take ::node (XTDB node) do not have :malli/schema
;; metadata because the node type cannot be generated for property testing.
;; The schemas are still documented in this file for reference.

;; Entity type discriminator
(schema/register! ::type
                  [:enum {:description "AI entity type"}
                   :session :message :tool-call])

;; Message roles (string per Claude/OpenAI convention)
(schema/register! ::role
                  [:enum {:description "Message role"}
                   "user" "assistant" "system"])

;; Message content - can be string or structured
(schema/register! ::content
                  [:or {:description "Message content"}
                   :string
                   [:map]])

;; Timestamp for events
;; Uses :gen/fmap to produce Instant values for generative testing
(schema/register! ::timestamp
                  [:fn {:description "Event timestamp"
                        :error/message "Must be a java.time.Instant"
                        :gen/fmap (fn [_] (Instant/now))
                        :gen/schema :int}
                   inst?])

;; Session ID reference
(schema/register! ::session-id
                  [:string {:min 1
                            :description "Parent session reference"}])

;; Message ID
(schema/register! ::message-id
                  [:string {:min 1
                            :description "Message identifier"}])

;; Status enum
(schema/register! ::status
                  [:enum {:description "Entity status"}
                   :active :completed :failed :interrupted])

;; Token counts
(schema/register! ::input-tokens
                  [:int {:min 0
                         :description "Input token count"}])

(schema/register! ::output-tokens
                  [:int {:min 0
                         :description "Output token count"}])

;; Cost tracking
(schema/register! ::cost-usd
                  [:double {:min 0.0
                            :description "Cost in USD"}])

;; Clojure namespace context (stored as string for XTDB compatibility)
(schema/register! ::namespace
                  [:string {:min 1
                            :description "Clojure namespace name"}])

;; Initial prompt
(schema/register! ::prompt
                  [:string {:min 1
                            :description "Initial prompt that started a session"}])

;; Error details
(schema/register! ::error
                  [:map {:description "Error details"}
                   [::message {:optional true} :string]
                   [::code {:optional true} :string]
                   [::data {:optional true} :map]])

;; XTDB node (opaque type, not validated beyond presence)
(schema/register! ::node
                  [:any {:description "XTDB node instance"}])

;; Limit for pagination
(schema/register! ::limit
                  [:int {:min 1 :max 1000
                         :description "Maximum number of results"}])

;;; ---------------------------------------------------------------------------
;;; Request/Response Schemas
;;; ---------------------------------------------------------------------------

;; Start session request
(schema/register! ::start-session-request
                  [:map
                   [::node ::node]
                   [::namespace {:optional true} ::namespace]
                   [::prompt {:optional true} ::prompt]])

;; Start session response
(schema/register! ::start-session-response
                  [:map
                   [::session-id ::session-id]])

;; End session request
(schema/register! ::end-session-request
                  [:map
                   [::node ::node]
                   [::session-id ::session-id]
                   [::status {:optional true} ::status]
                   [::input-tokens {:optional true} ::input-tokens]
                   [::output-tokens {:optional true} ::output-tokens]
                   [::cost-usd {:optional true} ::cost-usd]
                   [::error {:optional true} ::error]])

;; End session response
(schema/register! ::end-session-response
                  [:map
                   [::session-id ::session-id]
                   [::status ::status]])

;; Add message request
(schema/register! ::add-message-request
                  [:map
                   [::node ::node]
                   [::session-id ::session-id]
                   [::role ::role]
                   [::content ::content]
                   [::input-tokens {:optional true} ::input-tokens]
                   [::output-tokens {:optional true} ::output-tokens]])

;; Add message response
(schema/register! ::add-message-response
                  [:map
                   [::message-id ::message-id]])

;; Get session request
(schema/register! ::get-session-request
                  [:map
                   [::node ::node]
                   [::session-id ::session-id]])

;; Session entity (what get-session returns)
(schema/register! ::session-entity
                  [:map
                   [:xt/id ::session-id]
                   [::type [:= :session]]
                   [::status ::status]
                   [::started-at ::timestamp]
                   [::namespace {:optional true} ::namespace]
                   [::prompt {:optional true} ::prompt]
                   [::ended-at {:optional true} ::timestamp]
                   [::input-tokens {:optional true} ::input-tokens]
                   [::output-tokens {:optional true} ::output-tokens]
                   [::cost-usd {:optional true} ::cost-usd]
                   [::error {:optional true} ::error]])

;; Get messages request
(schema/register! ::get-messages-request
                  [:map
                   [::node ::node]
                   [::session-id ::session-id]])

;; Message entity
(schema/register! ::message-entity
                  [:map
                   [:xt/id ::message-id]
                   [::type [:= :message]]
                   [::session-id ::session-id]
                   [::role ::role]
                   [::content ::content]
                   [::timestamp ::timestamp]
                   [::input-tokens {:optional true} ::input-tokens]
                   [::output-tokens {:optional true} ::output-tokens]])

;; List sessions request
(schema/register! ::list-sessions-request
                  [:map
                   [::node ::node]
                   [::limit {:optional true} ::limit]
                   [::namespace {:optional true} ::namespace]
                   [::status {:optional true} ::status]])

;;; ---------------------------------------------------------------------------
;;; ID Generation
;;; ---------------------------------------------------------------------------

(defn- generate-id
  "Generate a unique ID with a prefix."
  [prefix]
  (str prefix "-" (UUID/randomUUID)))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn start-session!
  "Start a new AI session.

   Creates a session entity in XTDB with status :active.

   Request keys:
     ::node      - Required. XTDB node instance
     ::namespace - Optional. Clojure namespace context (symbol or string)
     ::prompt    - Optional. Initial prompt

   Response keys:
     ::session-id - The generated session ID

   Example:
     (start-session! {::node db ::namespace 'seon.trading ::prompt \"Analyze data\"})"
  [{::keys [node namespace prompt]}]
  (let [session-id (generate-id "ses")
        ;; Convert namespace to string for XTDB compatibility
        ns-str (when namespace (str namespace))
        entity (cond-> {:xt/id session-id
                        ::type :session
                        ::status :active
                        ::started-at (Instant/now)}
                 ns-str (assoc ::namespace ns-str)
                 prompt (assoc ::prompt prompt))]
    (db/put! node :ai_sessions entity)
    {::session-id session-id}))

(defn end-session!
  "End an AI session.

   Updates the session with final status and optional usage statistics.

   Request keys:
     ::node          - Required. XTDB node instance
     ::session-id    - Required. Session to end
     ::status        - Optional. Final status (default: :completed)
     ::input-tokens  - Optional. Total input tokens
     ::output-tokens - Optional. Total output tokens
     ::cost-usd      - Optional. Total cost
     ::error         - Optional. Error details if failed

   Response keys:
     ::session-id - The session ID
     ::status     - The final status

   Example:
     (end-session! {::node db
                    ::session-id \"ses-abc123\"
                    ::status :completed
                    ::input-tokens 1500
                    ::output-tokens 800})"
  [{::keys [node session-id status input-tokens output-tokens cost-usd error]}]
  (let [final-status (or status :completed)
        ;; Get existing session to preserve fields
        existing (db/entity node :ai_sessions session-id)
        updated (cond-> (or existing {:xt/id session-id ::type :session})
                  true (assoc ::status final-status
                              ::ended-at (Instant/now))
                  input-tokens (assoc ::input-tokens input-tokens)
                  output-tokens (assoc ::output-tokens output-tokens)
                  cost-usd (assoc ::cost-usd cost-usd)
                  error (assoc ::error error))]
    (db/put! node :ai_sessions updated)
    {::session-id session-id
     ::status final-status}))

(defn add-message!
  "Add a message to a session.

   Creates a message entity linked to the session.

   Request keys:
     ::node          - Required. XTDB node instance
     ::session-id    - Required. Parent session
     ::role          - Required. Message role (\"user\", \"assistant\", \"system\")
     ::content       - Required. Message content
     ::input-tokens  - Optional. Input tokens for this message
     ::output-tokens - Optional. Output tokens for this message

   Response keys:
     ::message-id - The generated message ID

   Example:
     (add-message! {::node db
                    ::session-id \"ses-abc123\"
                    ::role \"assistant\"
                    ::content \"I'll analyze the data...\"})"
  [{::keys [node session-id role content input-tokens output-tokens]}]
  (let [message-id (generate-id "msg")
        entity (cond-> {:xt/id message-id
                        ::type :message
                        ::session-id session-id
                        ::role role
                        ::content content
                        ::timestamp (Instant/now)}
                 input-tokens (assoc ::input-tokens input-tokens)
                 output-tokens (assoc ::output-tokens output-tokens))]
    (db/put! node :ai_messages entity)
    {::message-id message-id}))

(defn get-session
  "Get a session by ID.

   Request keys:
     ::node       - Required. XTDB node instance
     ::session-id - Required. Session ID

   Returns:
     Session entity map or nil if not found.

   Example:
     (get-session {::node db ::session-id \"ses-abc123\"})"
  [{::keys [node session-id]}]
  (db/entity node :ai_sessions session-id))

(defn get-messages
  "Get all messages for a session.

   Request keys:
     ::node       - Required. XTDB node instance
     ::session-id - Required. Session ID

   Returns:
     Vector of message entities, ordered by timestamp.

   Example:
     (get-messages {::node db ::session-id \"ses-abc123\"})"
  [{::keys [node session-id]}]
  (db/q node
        "SELECT * FROM ai_messages WHERE seon$ai$session_id = ? ORDER BY seon$ai$timestamp ASC"
        [session-id]))

(defn list-sessions
  "List recent sessions.

   Request keys:
     ::node      - Required. XTDB node instance
     ::limit     - Optional. Max results (default: 20, max: 1000)
     ::namespace - Optional. Filter by namespace
     ::status    - Optional. Filter by status

   Returns:
     Vector of session entities, ordered by started_at descending.

   Example:
     (list-sessions {::node db ::limit 10})
     (list-sessions {::node db ::namespace 'seon.trading ::status :active})"
  [{::keys [node limit namespace status]}]
  (let [limit (or limit 20)
        base-sql "SELECT * FROM ai_sessions"
        conditions (cond-> []
                     namespace (conj "seon$ai$namespace = ?")
                     status (conj "seon$ai$status = ?"))
        params (cond-> []
                 namespace (conj (str namespace))
                 status (conj (name status)))
        where-clause (when (seq conditions)
                       (str " WHERE " (clojure.string/join " AND " conditions)))
        sql (str base-sql where-clause " ORDER BY seon$ai$started_at DESC LIMIT ?")]
    (db/q node sql (conj params limit))))

(comment
  ;; REPL exploration

  ;; View registered schemas
  (require '[seon.schema :as schema])
  (schema/schemas-in-namespace "seon.ai")

  ;; Generate sample data
  (require '[malli.generator :as mg])
  (mg/generate ::role)
  (mg/generate ::content)
  (mg/generate ::status)

  ;; Validate data
  (require '[malli.core :as m])
  (m/validate ::role "assistant")
  (m/validate ::content "Hello world")
  (m/validate ::content {:type "text" :data "structured"})

  ;; Collect function schemas
  (require '[malli.instrument :as mi])
  (mi/collect! {:ns 'seon.ai})
  (keys (get (m/function-schemas) 'seon.ai))

  nil)
