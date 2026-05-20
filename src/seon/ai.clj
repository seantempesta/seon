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

   All functions use map-in, map-out with namespaced keys.
   All data is stored in Datahike.

   - `(start-session! {::namespace 'seon.trading ::prompt \"...\"})`
   - `(end-session! {::session-id \"...\" ::status :completed})`
   - `(add-message! {::session-id \"...\" ::role \"assistant\" ::content \"...\"})`
   - `(get-session {::session-id \"...\"})`
   - `(get-messages {::session-id \"...\"})`
   - `(list-sessions {::limit 20})`"
  (:require [seon.schema :as schema])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; AI persistence — currently a no-op; pending port to a :seon.ai datahike namespace
;;; ---------------------------------------------------------------------------

(defn- datalevin-write!
  "No-op stub. Pending port to register `:seon.ai` as a datahike-flow
   namespace and route this through `seon.db/transact!`."
  [_op _entity]
  ;; FIXME: port to :seon.ai datahike namespace via seon.db/transact!
  nil)

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

;; Entity type discriminator
(schema/register! ::type
                  [:enum {:description "AI entity type"}
                   :session :message :tool-call])

;; Message roles (string per Claude/OpenAI convention)
(schema/register! ::role
                  [:enum {:description "Message role"}
                   "user" "assistant" "system"])

;; Message content - can be string or structured map.
;; Datahike stores as string; structured content is serialized by the DB layer.
(schema/register! ::content
                  [:or {:description "Message content"
                        :seon.db/value-type :db.type/string}
                   :string
                   [:map]])

;; Timestamp for events
(schema/register! ::timestamp :inst)

;; Session start time
(schema/register! ::started-at :inst)

;; Session end time
(schema/register! ::ended-at :inst)

;; Monotonic sequence number for message ordering within same-millisecond writes
(schema/register! ::sequence
                  [:int {:min 0 :description "Monotonic message sequence number"}])

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

;; Clojure namespace context (stored as string for database compatibility)
(schema/register! ::namespace
                  [:string {:min 1
                            :description "Clojure namespace name"}])

;; Initial prompt
(schema/register! ::prompt
                  [:string {:min 1
                            :description "Initial prompt that started a session"}])

;; Agent session ID - 4-char hex ID used for MCP sessions and log files
(schema/register! ::agent-session-id
                  [:string {:min 1
                            :description "4-char hex Seon session ID (for log files)"}])

;; Error details
(schema/register! ::error
                  [:map {:description "Error details"}
                   [::message {:optional true} :string]
                   [::code {:optional true} :string]
                   [::data {:optional true} :map]])

;; Limit for pagination
(schema/register! ::limit
                  [:int {:min 1 :max 1000
                         :description "Maximum number of results"}])

;;; ---------------------------------------------------------------------------
;;; Initial Context Schemas (for agent launch)
;;; ---------------------------------------------------------------------------

;; File context - information about a single file provided to agent
(schema/register! ::file-context
                  [:map {:description "Context file provided to agent"}
                   [::path :string]
                   [::content :string]
                   [::language :string]
                   [::byte-count :int]
                   [::read-success :boolean]
                   [::error {:optional true} :string]])

;; Vector of file contexts
(schema/register! ::files-context
                  [:vector {:description "Files provided as context to agent"}
                   ::file-context])

;; Initial context - the full context provided to launch an agent
(schema/register! ::initial-context
                  [:map {:description "Initial context provided when launching agent"}
                   [::task-prompt :string]
                   [::files-context {:optional true} ::files-context]
                   [::agent-instructions {:optional true} :string]
                   [::agent-instructions-path {:optional true} :string]])

;;; ---------------------------------------------------------------------------
;;; Tool Call Schemas (Provider-Agnostic)
;;; ---------------------------------------------------------------------------

;; AI provider identifier
(schema/register! ::provider
                  [:enum {:description "AI provider"}
                   :claude :gemini :openai])

;; Single tool call from assistant
(schema/register! ::tool-call
                  [:map {:description "Single tool call from assistant"}
                   [:id :string]
                   [:name :string]
                   [:input {:optional true} :any]])

;; Vector of tool calls from assistant message
(schema/register! ::tool-calls
                  [:vector {:description "Tool calls from assistant message"}
                   ::tool-call])

;; Result of a tool call
(schema/register! ::tool-result
                  [:map {:description "Result of a tool call"}
                   [:tool-use-id :string]
                   [:content {:optional true} :any]
                   [:is-error {:optional true} :boolean]])

;; Vector of tool results in user message
(schema/register! ::tool-results
                  [:vector {:description "Tool results in user message"}
                   ::tool-result])

;;; ---------------------------------------------------------------------------
;;; Request/Response Schemas
;;; ---------------------------------------------------------------------------

;; Start session request
(schema/register! ::start-session-request
                  [:map
                   [::namespace {:optional true} ::namespace]
                   [::prompt {:optional true} ::prompt]
                   [::agent-session-id {:optional true} ::agent-session-id]
                   [::initial-context {:optional true} ::initial-context]])

;; Start session response
(schema/register! ::start-session-response
                  [:map
                   [::session-id ::session-id]])

;; End session request
(schema/register! ::end-session-request
                  [:map
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
                   [::session-id ::session-id]])

;; Session entity (what get-session returns)
(schema/register! ::session-entity
                  [:map
                   [:seon/id ::session-id]
                   [::type [:= :session]]
                   [::status ::status]
                   [::started-at ::timestamp]
                   [::namespace {:optional true} ::namespace]
                   [::prompt {:optional true} ::prompt]
                   [::agent-session-id {:optional true} ::agent-session-id]
                   [::initial-context {:optional true} ::initial-context]
                   [::ended-at {:optional true} ::timestamp]
                   [::input-tokens {:optional true} ::input-tokens]
                   [::output-tokens {:optional true} ::output-tokens]
                   [::cost-usd {:optional true} ::cost-usd]
                   [::error {:optional true} ::error]])

;; Get messages request
(schema/register! ::get-messages-request
                  [:map
                   [::session-id ::session-id]])

;; Message entity
(schema/register! ::message-entity
                  [:map
                   [:seon/id ::message-id]
                   [::type [:= :message]]
                   [::session-id ::session-id]
                   [::role ::role]
                   [::content ::content]
                   [::timestamp ::timestamp]
                   [::input-tokens {:optional true} ::input-tokens]
                   [::output-tokens {:optional true} ::output-tokens]
                   [::tool-calls {:optional true} ::tool-calls]
                   [::tool-results {:optional true} ::tool-results]
                   [::provider {:optional true} ::provider]])

;; List sessions request
(schema/register! ::list-sessions-request
                  [:map
                   [::limit {:optional true} ::limit]
                   [::namespace {:optional true} ::namespace]
                   [::status {:optional true} ::status]])

;;; ---------------------------------------------------------------------------
;;; Session Stats Schemas
;;; ---------------------------------------------------------------------------

;; Token breakdown for stats
(schema/register! ::tokens
                  [:map {:description "Token usage breakdown"}
                   [:input :int]
                   [:output :int]
                   [:cache-read :int]
                   [:cache-creation :int]])

;; Cache hit rate (ratio)
(schema/register! ::cache-hit-rate
                  [:double {:min 0.0 :max 1.0
                            :description "Cache hit rate: cache-read / (cache-read + input)"}])

;; Session stats request
(schema/register! ::session-stats-request
                  [:map])

;; Session stats response
(schema/register! ::session-stats-response
                  [:map
                   [::total-cost-usd :double]
                   [::total-sessions :int]
                   [::total-messages :int]
                   [::tokens ::tokens]
                   [::cache-hit-rate ::cache-hit-rate]])

;;; ---------------------------------------------------------------------------
;;; ID Generation
;;; ---------------------------------------------------------------------------

(defn- generate-id
  "Generate a unique ID with a prefix. Delegates to seon.runtime/generate-id."
  [prefix]
  (:seon.runtime/id ((requiring-resolve 'seon.runtime/generate-id)
                     {:seon.runtime/prefix prefix})))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn start-session!
  "Start a new AI session.

   Creates a session entity in Datahike with status :active.

   Request keys:
     ::namespace        - Optional. Clojure namespace context (symbol or string)
     ::prompt           - Optional. Initial prompt
     ::agent-session-id - Optional. 4-char hex Seon session ID (for log files)
     ::initial-context  - Optional. Initial context with task-prompt, files-context, agent-instructions

   Response keys:
     ::session-id - The generated session ID

   Example:
     (start-session! {::namespace 'seon.trading ::prompt \"Analyze data\"})"
  [{::keys [namespace prompt agent-session-id initial-context]}]
  (let [session-id (generate-id "ses")
        ;; Convert namespace to string for database compatibility
        ns-str (when namespace (str namespace))
        entity (cond-> {:seon/id session-id
                        ::type :session
                        ::status :active
                        ::started-at (Instant/now)}
                 ns-str (assoc ::namespace ns-str)
                 prompt (assoc ::prompt prompt)
                 agent-session-id (assoc ::agent-session-id agent-session-id)
                 initial-context (assoc ::initial-context initial-context))]
    (datalevin-write! :save-session entity)
    {::session-id session-id}))

(defn end-session!
  "End an AI session.

   Updates the session with final status and optional usage statistics.

   Request keys:
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
     (end-session! {::session-id \"ses-abc123\"
                    ::status :completed
                    ::input-tokens 1500
                    ::output-tokens 800})"
  [{::keys [session-id status input-tokens output-tokens cost-usd error]}]
  ;; FIXME(M-3): port to :seon.ai datahike namespace via seon.db/transact!
  (let [final-status (or status :completed)
        updated (cond-> {:seon/id session-id ::type :session
                         ::status final-status
                         ::ended-at (Instant/now)}
                  input-tokens (assoc ::input-tokens input-tokens)
                  output-tokens (assoc ::output-tokens output-tokens)
                  cost-usd (assoc ::cost-usd cost-usd)
                  error (assoc ::error error))]
    (datalevin-write! :update-session updated)
    {::session-id session-id
     ::status final-status}))

(defn add-message!
  "Add a message to a session.

   Creates a message entity linked to the session.

   Request keys:
     ::session-id    - Required. Parent session
     ::role          - Required. Message role (\"user\", \"assistant\", \"system\")
     ::content       - Required. Message content
     ::input-tokens  - Optional. Input tokens for this message
     ::output-tokens - Optional. Output tokens for this message

   Response keys:
     ::message-id - The generated message ID

   Example:
     (add-message! {::session-id \"ses-abc123\"
                    ::role \"assistant\"
                    ::content \"I'll analyze the data...\"})"
  [{::keys [session-id role content input-tokens output-tokens]}]
  (let [message-id (generate-id "msg")
        entity (cond-> {:seon/id message-id
                        ::type :message
                        ::session-id session-id
                        ::role role
                        ::content content
                        ::timestamp (Instant/now)}
                 input-tokens (assoc ::input-tokens input-tokens)
                 output-tokens (assoc ::output-tokens output-tokens))]
    (datalevin-write! :save-message entity)
    {::message-id message-id}))

(defn get-session
  "Get a session by ID.

   Request keys:
     ::session-id - Required. Session ID

   Returns:
     Session entity map or nil if not found.

   Example:
     (get-session {::session-id \"ses-abc123\"})"
  [{::keys [_session-id]}]
  ;; FIXME(M-3): port to :seon.ai datahike namespace via seon.db/query.
  nil)

(defn get-messages
  "Get all messages for a session.

   Request keys:
     ::session-id - Required. Session ID

   Returns:
     Vector of message entities, ordered by timestamp.

   Example:
     (get-messages {::session-id \"ses-abc123\"})"
  [{::keys [_session-id]}]
  ;; FIXME(M-3): port to :seon.ai datahike namespace via seon.db/query.
  [])

(defn list-sessions
  "List recent sessions.

   Request keys:
     ::limit     - Optional. Max results (default: 20, max: 1000)
     ::namespace - Optional. Filter by namespace
     ::status    - Optional. Filter by status

   Returns:
     Vector of session entities, ordered by started_at descending.

   Example:
     (list-sessions {::limit 10})
     (list-sessions {::namespace 'seon.trading ::status :active})"
  [{::keys [_limit _namespace _status]}]
  ;; FIXME(M-3): port to :seon.ai datahike namespace via seon.db/query.
  [])

(defn session-stats
  "Get aggregate statistics across all AI sessions.

   Response keys:
     ::total-cost-usd  - Total cost across all sessions
     ::total-sessions  - Number of sessions
     ::total-messages  - Number of messages
     ::tokens          - Map with :input, :output, :cache-read, :cache-creation
     ::cache-hit-rate  - Ratio of cache-read / (cache-read + input)

   Example:
     (session-stats {})"
  [_request]
  ;; FIXME(M-3): port to :seon.ai datahike namespace via seon.db/query.
  {::total-cost-usd 0
   ::total-sessions 0
   ::total-messages 0
   ::tokens {:input 0 :output 0 :cache-read 0 :cache-creation 0}
   ::cache-hit-rate 0.0})

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

  nil)
