(ns seon.ai.claude
  "Claude Code provider namespace extending seon.ai base.

   This namespace provides Claude-specific schemas and functions for agent
   lifecycle management. It extends the base seon.ai schemas with Claude-specific
   attributes like caching tokens, tool use, and SDK message types.

   ## Schemas (extending seon.ai base)

   - `::model` - Claude model identifier
   - `::cache-creation-tokens`, `::cache-read-tokens` - Prompt caching
   - `::tool-calls`, `::tool-results` - Tool use blocks
   - `::message-type` - SDK message type ('assistant', 'result', etc.)
   - `::uuid` - SDK-assigned message UUID
   - `::raw-message` - Full SDK message for debugging

   ## Functions

   - `(launch-agent! {...})` - Spawn Claude agent with session
   - `(agents {})` - List running agents
   - `(interrupt! {::session-id \"...\"}` - Stop agent
   - `(tail {::session-id \"...\"}` - Stream messages from agent
   - `(sdk-message->entity msg)` - Convert SDK message to entity

   Example:

     (require '[seon.ai.claude :as claude])
     (require '[seon.ai :as ai])

     ;; Launch an agent
     (claude/launch-agent! {::ai/node xtdb-node
                            ::ai/namespace 'seon.trading
                            ::ai/prompt \"Implement feature X\"})

     ;; List running agents
     (claude/agents {})

     ;; Interrupt an agent
     (claude/interrupt! {::ai/session-id \"a1b2\"})"
  (:require
   [clojure.core.async :as async :refer [chan close!]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [seon.ai :as ai]
   [seon.ai.agent :as agent]
   [seon.ai.agent.log :as agent-log]
   [seon.ai.claude.sdk :as sdk]
   [seon.orchestrator.session :as session]
   [seon.schema :as schema]
   [taoensso.timbre :as log])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Claude-Specific Schema Registration
;;; ---------------------------------------------------------------------------

;; Model identifiers - Opus 4.5 is default for complex tasks
(schema/register! ::model
                  [:enum {:description "Claude model identifier"}
                   "claude-opus-4-5-20251101"
                   "claude-sonnet-4-20250514"
                   "claude-3-5-haiku-20241022"])

;; Prompt caching tokens
(schema/register! ::cache-creation-tokens
                  [:int {:min 0
                         :description "Tokens used to create prompt cache"}])

(schema/register! ::cache-read-tokens
                  [:int {:min 0
                         :description "Tokens read from prompt cache"}])

;; Tool use - blocks from assistant messages
(schema/register! ::tool-call
                  [:map {:description "Single tool use block"}
                   [:id :string]
                   [:name :string]
                   [:input {:optional true} :any]])

(schema/register! ::tool-calls
                  [:vector {:description "Tool use blocks from assistant message"}
                   ::tool-call])

;; Tool results - blocks from user messages
(schema/register! ::tool-result
                  [:map {:description "Single tool result block"}
                   [:tool_use_id :string]
                   [:content {:optional true} :any]
                   [:is_error {:optional true} :boolean]])

(schema/register! ::tool-results
                  [:vector {:description "Tool result blocks"}
                   ::tool-result])

;; SDK message types
(schema/register! ::message-type
                  [:enum {:description "Claude SDK message type"}
                   "system" "assistant" "user" "result" "keep_alive" "parse_error"])

;; SDK message UUID
(schema/register! ::uuid
                  [:string {:min 1
                            :description "SDK-assigned message UUID"}])

;; Raw SDK message for debugging
(schema/register! ::raw-message
                  [:map {:description "Full SDK message for debugging"}])

;; Agent status
(schema/register! ::agent-status
                  [:enum :running :completed :failed :terminated :interrupted])

;; Result subtypes from Claude SDK
(schema/register! ::result-subtype
                  [:enum "success" "error_during_execution" "error_max_turns"
                   "error_max_budget_usd" "interrupted"])

;;; ---------------------------------------------------------------------------
;;; Composite Schemas (referencing base seon.ai schemas)
;;; ---------------------------------------------------------------------------

;; SDK message schema
(schema/register! ::sdk-message
                  [:map
                   [:type ::message-type]
                   [:session_id {:optional true} :string]
                   [:uuid {:optional true} :string]
                   [:message {:optional true} :map]
                   [:subtype {:optional true} ::result-subtype]
                   [:result {:optional true} :string]
                   [:num_turns {:optional true} :int]
                   [:total_cost_usd {:optional true} :double]
                   [:duration_ms {:optional true} :int]
                   [:is_error {:optional true} :boolean]])

;; Message entity with Claude-specific fields
;; Extends base seon.ai message with Claude attributes
(schema/register! ::message-entity
                  [:map
                   [:xt/id ::ai/message-id]
                   [::ai/type [:= :message]]
                   [::ai/session-id ::ai/session-id]
                   [::ai/role ::ai/role]
                   [::ai/content ::ai/content]
                   [::ai/timestamp ::ai/timestamp]  ; Base schema now has generator
                   ;; Claude-specific (optional)
                   [::message-type {:optional true} ::message-type]
                   [::uuid {:optional true} ::uuid]
                   [::tool-calls {:optional true} ::tool-calls]
                   [::tool-results {:optional true} ::tool-results]
                   [::cache-creation-tokens {:optional true} ::cache-creation-tokens]
                   [::cache-read-tokens {:optional true} ::cache-read-tokens]
                   [::raw-message {:optional true} ::raw-message]])

;; Launch request - uses base schemas where appropriate
;; SDK-related schemas (permission-mode, max-turns, etc.) are in seon.ai.claude.sdk
(schema/register! ::launch-agent-request
                  [:map
                   [::ai/node ::ai/node]
                   [::ai/namespace ::ai/namespace]
                   [::ai/prompt ::ai/prompt]
                   [::model {:optional true} ::model]
                   [::sdk/permission-mode {:optional true} ::sdk/permission-mode]
                   [::sdk/max-turns {:optional true} ::sdk/max-turns]
                   [::sdk/max-budget-usd {:optional true} ::sdk/max-budget-usd]
                   [::sdk/allowed-tools {:optional true} ::sdk/allowed-tools]
                   [::sdk/disallowed-tools {:optional true} ::sdk/disallowed-tools]])

;; Agent handle returned from launch-agent!
(schema/register! ::agent-handle
                  [:map
                   [::ai/session-id ::ai/session-id]
                   [::ai/namespace ::ai/namespace]
                   [::nrepl-port :int]
                   [::messages-ch :any]    ; core.async channel
                   [::result-ch :any]      ; core.async channel
                   [::status-atom :any]    ; atom with agent status
                   [::close! fn?]])        ; function to terminate

;; Agent summary for list views
(schema/register! ::agent-summary
                  [:map
                   [::ai/session-id ::ai/session-id]
                   [::ai/namespace ::ai/namespace]
                   [::nrepl-port :int]
                   [::agent-status ::agent-status]])

;; Interrupt request/response
(schema/register! ::interrupt-request
                  [:map
                   [::ai/session-id ::ai/session-id]])

(schema/register! ::interrupt-response
                  [:map
                   [::ai/session-id ::ai/session-id]
                   [::interrupted? :boolean]
                   [::method {:optional true} [:enum :sigint :destroy]]
                   [::error {:optional true} :string]])

;; Tail request
(schema/register! ::tail-request
                  [:map
                   [::ai/session-id ::ai/session-id]])

;; Get agent request
(schema/register! ::get-agent-request
                  [:map
                   [::ai/session-id ::ai/session-id]])

;; Agents (list) request/response
(schema/register! ::agents-request
                  [:map])

(schema/register! ::agents-response
                  [:vector ::agent-summary])

;; Note: sdk-message->entity and agent lifecycle functions do not have
;; :malli/schema metadata because they involve XTDB nodes, process spawning,
;; or runtime-generated values that cannot be property tested.
;; Schemas are documented in docstrings for reference.

;;; ---------------------------------------------------------------------------
;;; SDK Message Conversion
;;; ---------------------------------------------------------------------------

(defn- extract-tool-calls
  "Extract tool_use blocks from assistant message content."
  [content]
  (when (sequential? content)
    (->> content
         (filter #(= "tool_use" (:type %)))
         (mapv #(select-keys % [:id :name :input]))
         not-empty)))

(defn- extract-tool-results
  "Extract tool_result blocks from user message content."
  [content]
  (when (sequential? content)
    (->> content
         (filter #(= "tool_result" (:type %)))
         (mapv #(select-keys % [:tool_use_id :content :is_error]))
         not-empty)))

(defn- extract-text-content
  "Extract text content from message, handling both string and structured content."
  [content]
  (cond
    (string? content) content
    (sequential? content)
    (->> content
         (filter #(= "text" (:type %)))
         (map :text)
         (str/join "\n"))
    :else (str content)))

(defn sdk-message->entity
  "Convert a Claude SDK message to a seon.ai message entity.

   Takes a request map with the SDK message and converts it to a normalized
   entity suitable for XTDB storage. Claude-specific attributes are preserved
   under the ::seon.ai.claude namespace.

   Request keys:
     ::sdk-message - Required. Raw SDK message map with keys like :type, :message, :uuid
     ::ai/session-id - Optional. Parent AI session ID to attach to entity

   Response keys:
     Returns a message entity map with :xt/id and namespaced attributes

   Example:
     (sdk-message->entity {::sdk-message {:type \"assistant\"
                                          :uuid \"msg-123\"
                                          :message {:role \"assistant\"
                                                    :content [{:type \"text\" :text \"Hello\"}]}}})
     ;; => {:xt/id \"msg-abc123\"
     ;;     :seon.ai/type :message
     ;;     :seon.ai/role \"assistant\"
     ;;     :seon.ai/content \"Hello\"
     ;;     :seon.ai.claude/message-type \"assistant\"
     ;;     :seon.ai.claude/uuid \"msg-123\"
     ;;     ...}

   Note: This function does not have :malli/schema metadata because the output
   contains runtime-generated values (timestamps, UUIDs) that cannot be
   property tested. Schemas are documented above for reference."
  [{::keys [sdk-message] ::ai/keys [session-id]}]
  (let [sdk-msg sdk-message
        msg-type (:type sdk-msg)
        inner-msg (:message sdk-msg)
        role (or (:role inner-msg)
                 (case msg-type
                   "assistant" "assistant"
                   "user" "user"
                   "system" "system"
                   "result" "assistant"
                   "assistant"))
        content (or (:content inner-msg) (:result sdk-msg) "")
        text-content (extract-text-content content)
        tool-calls (when (= role "assistant")
                     (extract-tool-calls content))
        tool-results (when (= role "user")
                       (extract-tool-results content))
        ;; Extract usage if present (mainly in result messages)
        usage (:usage sdk-msg)]
    (cond-> {:xt/id (str "msg-" (java.util.UUID/randomUUID))
             ::ai/type :message
             ::ai/role role
             ::ai/content text-content
             ::ai/timestamp (Instant/now)
             ::message-type msg-type}
      ;; Session reference
      session-id
      (assoc ::ai/session-id session-id)

      ;; Claude-specific optional fields
      (:uuid sdk-msg)
      (assoc ::uuid (:uuid sdk-msg))

      tool-calls
      (assoc ::tool-calls tool-calls)

      tool-results
      (assoc ::tool-results tool-results)

      ;; Token usage from result messages
      (:input_tokens usage)
      (assoc ::ai/input-tokens (:input_tokens usage))

      (:output_tokens usage)
      (assoc ::ai/output-tokens (:output_tokens usage))

      (:cache_creation_input_tokens usage)
      (assoc ::cache-creation-tokens (:cache_creation_input_tokens usage))

      (:cache_read_input_tokens usage)
      (assoc ::cache-read-tokens (:cache_read_input_tokens usage)))))

;;; ---------------------------------------------------------------------------
;;; Provider Multimethod Implementations (seon.ai.agent)
;;; ---------------------------------------------------------------------------

;; normalize-message :claude
;; Convert Claude SDK message to ::ai/message entity.
;; The message is expected to be from the Claude Code CLI streaming output.
;; Converts to the normalized entity format used throughout Seon.
;;
;; Request keys:
;;   :provider   - :claude
;;   :message    - Claude SDK message map (with :type, :message, :uuid, etc.)
;;   :session-id - Optional. AI session ID to attach
;;
;; Returns entity map suitable for XTDB storage.
(defmethod agent/normalize-message :claude
  [{:keys [message session-id]}]
  (sdk-message->entity (cond-> {::sdk-message message}
                         session-id (assoc ::ai/session-id session-id))))

;; result-message? :claude
;; Check if a Claude message is the final result.
;; Claude Code CLI sends a message with type="result" when the agent
;; completes (successfully, with error, or interrupted).
;;
;; Request keys:
;;   :provider - :claude
;;   :message  - Claude SDK message map
;;
;; Returns true if message type is "result".
(defmethod agent/result-message? :claude
  [{:keys [message]}]
  (= "result" (:type message)))

;; parse-result :claude
;; Extract stats from a Claude result message.
;;
;; Claude result messages contain:
;;   - :subtype - "success", "error_during_execution", "error_max_turns",
;;                "error_max_budget_usd", "interrupted"
;;   - :total_cost_usd - Total cost
;;   - :num_turns - Number of turns
;;   - :duration_ms - Duration in milliseconds
;;   - :result - Final text result
;;
;; Request keys:
;;   :provider - :claude
;;   :message  - Claude SDK result message
;;
;; Returns parsed result map with normalized keys.
(defmethod agent/parse-result :claude
  [{:keys [message]}]
  (let [subtype (:subtype message)
        status (case subtype
                 "success" :completed
                 "interrupted" :interrupted
                 ;; All error subtypes map to :failed
                 ("error_during_execution" "error_max_turns" "error_max_budget_usd") :failed
                 ;; Unknown subtype defaults to :error
                 :error)]
    (cond-> {::agent/status status}
      (:total_cost_usd message)
      (assoc ::agent/cost-usd (:total_cost_usd message))

      (:num_turns message)
      (assoc ::agent/num-turns (:num_turns message))

      (:duration_ms message)
      (assoc ::agent/duration-ms (:duration_ms message))

      (:result message)
      (assoc ::agent/result-text (:result message))

      subtype
      (assoc ::agent/subtype subtype))))

(defn persist-message!
  "Persist a Claude SDK message to XTDB as an AI message entity.

   Converts the SDK message to an entity and stores it in the ai_messages table.
   This is used internally by launch-agent! to auto-persist all messages.

   Request keys:
     ::ai/node       - Required. XTDB node instance
     ::ai/session-id - Required. AI session ID (from ai/start-session!)
     ::sdk-message   - Required. Raw SDK message from Claude Code CLI

   Response keys:
     ::ai/message-id - The generated message ID

   Example:
     (persist-message! {::ai/node db
                        ::ai/session-id \"ses-abc123\"
                        ::sdk-message {:type \"assistant\" ...}})

   Note: This function does not have :malli/schema metadata because it
   takes XTDB nodes which cannot be property tested."
  [{::ai/keys [node session-id] ::keys [sdk-message]}]
  (let [entity (sdk-message->entity {::sdk-message sdk-message
                                      ::ai/session-id session-id})
        message-id (:xt/id entity)]
    ;; Store using seon.db.node (already required via seon.ai)
    ((requiring-resolve 'seon.db.node/put!) node :ai_messages entity)
    {::ai/message-id message-id}))

;;; ---------------------------------------------------------------------------
;;; Private Agent Helpers
;;; ---------------------------------------------------------------------------

(defn- build-agent-mcp-config
  "Build MCP config that passes session_id to the agent."
  [session-id]
  {:seon {:command "./bin/mcp-server"
          :env {"SEON_SESSION_ID" session-id}}})

(def ^:private agent-instructions-path ".claude/AGENT.md")

(defn- load-agent-instructions
  "Load agent instructions from AGENT.md, returning empty string if not found."
  []
  (let [f (io/file agent-instructions-path)]
    (if (.exists f)
      (str (slurp f) "\n\n---\n\n")
      "")))

(defn- build-agent-prompt
  "Build the agent prompt with session context and AGENT.md instructions."
  [session-id namespace prompt]
  (str (load-agent-instructions)
       "# Session Context\n\n"
       "- **Session ID**: " session-id "\n"
       "- **Namespace**: " namespace "\n\n"
       "## MCP Tools\n\n"
       "To evaluate Clojure code:\n\n"
       "```\n"
       "eval(session_id=\"" session-id "\", code=\"(your-code-here)\")\n"
       "```\n\n"
       "Your context atom `*ctx*` is available with namespaced keys:\n\n"
       "```clojure\n"
       "(swap! *ctx* assoc :" namespace "/data [...])\n"
       "(:" namespace "/data @*ctx*)\n"
       "```\n\n"
       "Helper functions (qualify with user/):\n\n"
       "```clojure\n"
       "(user/reload)           ; Reload changed code\n"
       "(user/search \"query\")  ; Web search via Gemini\n"
       "(user/status)           ; System status\n"
       "```\n\n"
       "---\n\n"
       "# Your Task\n\n" prompt))

;;; ---------------------------------------------------------------------------
;;; Agent Registry (uses shared seon.ai.agent registry)
;;; ---------------------------------------------------------------------------

;; NOTE: The agent registry has been moved to seon.ai.agent for cross-provider
;; observability. Claude agents register in agent/agent-registry with the
;; required fields (::agent/session-id, ::agent/namespace, ::agent/provider,
;; ::agent/status-atom, ::agent/close!, ::agent/messages-ch) plus Claude-specific
;; fields (::nrepl-port, ::ai-session-id, ::result-ch).

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn- persistable-message-type?
  "Check if message type should be persisted to XTDB.
   We persist user, assistant, system, and result messages.
   We skip keep_alive and parse_error messages."
  [msg-type]
  (#{"user" "assistant" "system" "result"} msg-type))

(defn launch-agent!
  "Launch a Claude Code agent with an isolated Seon session.

   Creates everything the agent needs:
   - Isolated XTDB database for the namespace
   - Persisted ctx atom for state management
   - Dedicated nREPL server
   - Claude Code process with MCP configured
   - AI session in XTDB for conversation persistence

   All SDK messages are automatically persisted to XTDB during execution.
   On completion, the AI session is closed with final stats (tokens, cost).

   Request keys:
     ::ai/node               - Required. XTDB orchestrator node
     ::ai/namespace          - Required. Agent namespace (string or symbol)
     ::ai/prompt             - Required. Task description for the agent
     ::model                 - Optional. Claude model (default: opus)
     ::sdk/permission-mode   - Optional. Permission mode (default: bypassPermissions)
     ::sdk/max-turns         - Optional. Max conversation turns
     ::sdk/max-budget-usd    - Optional. Cost limit
     ::sdk/allowed-tools     - Optional. Tool whitelist
     ::sdk/disallowed-tools  - Optional. Tool denylist
     ::ai/force?             - Optional. Force launch even if namespace has running agent

   Response keys (agent handle):
     ::ai/session-id      - 4-char hex session ID (Seon agent session)
     ::ai-session-id      - AI conversation session ID (for XTDB queries)
     ::ai/namespace       - Agent namespace
     ::nrepl-port         - nREPL port for the agent
     ::messages-ch        - Channel of SDK messages
     ::result-ch          - Channel receiving final result
     ::status-atom        - Atom with current status (:running, :completed, :failed)
     ::close!             - Function to terminate agent

   Example:
     (launch-agent! {::ai/node xtdb-node
                     ::ai/namespace 'seon.trading
                     ::ai/prompt \"Implement the signals dashboard\"})"
  [{::ai/keys [node namespace prompt force?]
    ::keys [model]
    ::sdk/keys [permission-mode max-turns max-budget-usd allowed-tools disallowed-tools]}]
  (log/info "Launching agent" {:namespace namespace})

  ;; Check for existing running agents on the same namespace
  ;; Use agent/agents directly since our `agents` fn is defined later in this file
  (let [ns-str (str namespace)
        existing (->> (agent/agents {})
                      (filter #(and (= ns-str (:seon.ai.agent/namespace %))
                                    (= :running (:seon.ai.agent/status %))))
                      first)]
    (when existing
      (if force?
        (log/warn "Launching agent on namespace with existing running agent"
                  {:namespace namespace
                   :existing-session (:seon.ai.agent/session-id existing)
                   :force? true})
        (throw (ex-info "Namespace already has a running agent. Use ::ai/force? true to override."
                        {:namespace namespace
                         :existing-session (:seon.ai.agent/session-id existing)})))))

  ;; 1. Create Seon session (nREPL, ctx, db)
  (let [{::session/keys [id nrepl-port] :as session-result}
        (session/start-agent-session! {::session/node node
                                       ::session/namespace namespace})]

    (when (= :error (::session/status session-result))
      (throw (ex-info "Failed to create agent session"
                      {:namespace namespace
                       :error (::session/error session-result)})))

    (log/info "Created agent session" {:session-id id :port nrepl-port})

    ;; 2. Create AI session for conversation persistence
    ;;    Store the Seon session ID so completed sessions can find their log files
    (let [{ai-session-id ::ai/session-id}
          (ai/start-session! {::ai/node node
                              ::ai/namespace namespace
                              ::ai/prompt prompt
                              ::ai/agent-session-id id})
          _ (log/info "Created AI session for persistence" {:ai-session-id ai-session-id})

          ;; 2b. Create structured agent log for real-time tailing
          agent-logger (agent-log/create-logger! {::agent-log/session-id id})
          _ (agent-log/log-launch! agent-logger {::agent-log/namespace (str namespace)
                                                  ::agent-log/port nrepl-port})

          ;; 3. Build agent prompt with session context
          full-prompt (build-agent-prompt id namespace prompt)

          ;; 4. Build MCP config with session_id in environment
          mcp-config (build-agent-mcp-config id)

          ;; 5. Spawn Claude Code with configured MCP
          status-atom (atom :running)
          {:keys [process stdin stdout]}
          (sdk/spawn-claude-code {::sdk/model (or model sdk/default-model)
                                  ::sdk/permission-mode (or permission-mode "bypassPermissions")
                                  ::sdk/max-turns max-turns
                                  ::sdk/max-budget-usd max-budget-usd
                                  ::sdk/allowed-tools allowed-tools
                                  ::sdk/disallowed-tools disallowed-tools
                                  ::sdk/mcp-servers mcp-config})

          messages-ch (chan 100)
          result-ch (chan 1)

          ;; 5b. Register process exit watcher to unblock reader on unexpected death
          ;; This handles the case where Claude crashes without sending a result message
          ;; and the readLine call blocks indefinitely waiting for more data.
          _ (-> (.onExit process)
                (.thenAccept
                 (reify java.util.function.Consumer
                   (accept [_ _exit-code]
                     (when (= :running @status-atom)
                       (log/info "Process exited while agent was running, closing streams"
                                 {:session-id id})
                       ;; Close stdout to unblock the reader's readLine call
                       (try
                         (.close stdout)
                         (catch Exception e
                           (log/debug "Error closing stdout on process exit" {:error (str e)}))))))))

          ;; 6. Start reader that persists messages and updates status
          claude-session-mapped? (atom false)
          _reader (future
                    (try
                      (with-open [rdr (io/reader stdout)]
                        (loop []
                          (when-let [line (.readLine rdr)]
                            (when-not (str/blank? line)
                              (let [msg (sdk/parse-line line)
                                    msg-type (:type msg)
                                    claude-session-id (:session_id msg)]
                                ;; Map Claude's session_id to our Seon session_id (once)
                                (when (and claude-session-id
                                           (not @claude-session-mapped?))
                                  (let [map-file (io/file "logs" "session-map.edn")
                                        existing (if (.exists map-file)
                                                   (try (read-string (slurp map-file))
                                                        (catch Exception _ {}))
                                                   {})]
                                    (spit map-file (pr-str (assoc existing claude-session-id id)))
                                    (reset! claude-session-mapped? true)
                                    (log/info "Mapped Claude session to Seon session"
                                              {:claude-session claude-session-id :seon-session id})))

                                ;; Persist message to XTDB (skip keep_alive, parse_error)
                                (when (persistable-message-type? msg-type)
                                  (try
                                    (persist-message! {::ai/node node
                                                       ::ai/session-id ai-session-id
                                                       ::sdk-message msg})
                                    (catch Exception e
                                      (log/warn e "Failed to persist message"
                                                {:session-id id :msg-type msg-type}))))

                                ;; Log to agent log file for real-time tailing
                                (agent-log/log-sdk-message! agent-logger msg)

                                (log/trace "Agent message" {:session-id id :type msg-type})
                                (async/>!! messages-ch msg)

                                ;; Handle result message - end AI session with stats
                                (when (= msg-type "result")
                                  (async/>!! result-ch msg)
                                  ;; End AI session with final stats
                                  (let [final-status (if (= "success" (:subtype msg))
                                                       :completed
                                                       :failed)]
                                    (try
                                      (ai/end-session! {::ai/node node
                                                        ::ai/session-id ai-session-id
                                                        ::ai/status final-status
                                                        ::ai/cost-usd (:total_cost_usd msg)})
                                      (log/info "Ended AI session"
                                                {:ai-session-id ai-session-id
                                                 :status final-status
                                                 :cost (:total_cost_usd msg)
                                                 :turns (:num_turns msg)})
                                      (catch Exception e
                                        (log/warn e "Failed to end AI session"
                                                  {:ai-session-id ai-session-id})))
                                    ;; Update agent status
                                    (reset! status-atom final-status)))))
                            (recur))))
                      (catch Exception e
                        (log/warn e "Agent reader error" {:session-id id})
                        (reset! status-atom :failed)
                        ;; End AI session as failed on reader error
                        (try
                          (ai/end-session! {::ai/node node
                                            ::ai/session-id ai-session-id
                                            ::ai/status :failed
                                            ::ai/error {::ai/message (.getMessage e)}})
                          (catch Exception _)))
                      (finally
                        ;; If status is still :running, the process ended without
                        ;; sending a result message (crash, external kill, etc.)
                        (when (= :running @status-atom)
                          (log/info "Agent reader ended without result message"
                                    {:session-id id})
                          (reset! status-atom :terminated)
                          ;; End AI session as terminated
                          (try
                            (ai/end-session! {::ai/node node
                                              ::ai/session-id ai-session-id
                                              ::ai/status :terminated})
                            (catch Exception _)))
                        ;; Clean up channels
                        (close! messages-ch)
                        (close! result-ch)
                        ;; Close agent logger
                        (agent-log/close-logger! agent-logger)
                        ;; Remove from registry now that agent is done
                        (swap! agent/agent-registry dissoc id)
                        (log/info "Agent cleanup complete" {:session-id id
                                                            :final-status @status-atom}))))

          ;; 7. Build close function that cleans up everything
          close-fn (fn close-agent []
                     (log/info "Terminating agent" {:session-id id})
                     ;; Destroy Claude process
                     (try
                       (.destroy process)
                       (catch Exception e
                         (log/warn e "Error destroying Claude process")))
                     ;; Close channels
                     (close! messages-ch)
                     (close! result-ch)
                     ;; Close agent logger
                     (agent-log/close-logger! agent-logger)
                     ;; End AI session as interrupted if still active
                     (when (= :running @status-atom)
                       (try
                         (ai/end-session! {::ai/node node
                                           ::ai/session-id ai-session-id
                                           ::ai/status :interrupted})
                         (catch Exception e
                           (log/warn e "Failed to end AI session on close"))))
                     ;; Stop Seon session (flushes ctx, stops nREPL)
                     (session/stop-agent-session! {::session/node node
                                                   ::session/id id})
                     ;; Remove from shared registry
                     (swap! agent/agent-registry dissoc id)
                     ;; Update status
                     (when (= :running @status-atom)
                       (reset! status-atom :terminated)))

          ;; Handle uses ::agent/ prefixed keys for shared registry compatibility
          ;; plus Claude-specific keys
          handle {;; Required by seon.ai.agent registry
                  ::agent/session-id id
                  ::agent/namespace (str namespace)
                  ::agent/provider :claude
                  ::agent/status-atom status-atom
                  ::agent/close! close-fn
                  ::agent/messages-ch messages-ch
                  ;; Claude-specific fields
                  ::agent/nrepl-port nrepl-port
                  ::agent/ai-session-id ai-session-id
                  ::result-ch result-ch
                  ;; Legacy aliases for backwards compatibility
                  ::ai/session-id id
                  ::ai-session-id ai-session-id
                  ::ai/namespace (str namespace)
                  ::nrepl-port nrepl-port
                  ::messages-ch messages-ch
                  ::status-atom status-atom
                  ::close! close-fn}]

      ;; 8. Send initial prompt
      (sdk/write-message! stdin (sdk/make-user-message full-prompt))

      ;; 9. Register agent in shared registry
      (swap! agent/agent-registry assoc id handle)

      (log/info "Agent launched" {:session-id id
                                  :ai-session-id ai-session-id
                                  :namespace namespace
                                  :port nrepl-port})

      handle)))

(defn agents
  "List all running Claude agents with status, namespace, session-id.

   Delegates to seon.ai.agent/agents and filters for Claude agents,
   mapping response keys to Claude-specific namespace for backwards compatibility.

   Request keys:
     (none - empty map for consistency)

   Response keys (vector of):
     ::ai/session-id  - 4-char hex session ID
     ::ai/namespace   - Agent namespace
     ::nrepl-port     - nREPL port for direct REPL access
     ::agent-status   - Current status (:running, :completed, :failed, :terminated)

   Example:
     (agents {})
     ;; => [{:seon.ai/session-id \"a1b2\"
     ;;      :seon.ai/namespace \"seon.trading\"
     ;;      :seon.ai.claude/nrepl-port 7889
     ;;      :seon.ai.claude/agent-status :running}]

   Note: For cross-provider agent listing, use seon.ai.agent/agents instead."
  [_request]
  (->> (agent/agents {})
       (filter #(= :claude (::agent/provider %)))
       (mapv (fn [a]
               {::ai/session-id (::agent/session-id a)
                ::ai/namespace (::agent/namespace a)
                ::nrepl-port (::agent/nrepl-port a)
                ::agent-status (::agent/agent-status a)}))))

(defn interrupt!
  "Interrupt a Claude agent.

   Delegates to seon.ai.agent/interrupt! and maps response keys
   to Claude-specific namespace for backwards compatibility.

   Request keys:
     ::ai/session-id - Required. The 4-char hex session ID

   Response keys:
     ::ai/session-id  - The session that was interrupted
     ::interrupted?   - Whether the interrupt succeeded
     ::method         - Method used (:close -> :destroy for backwards compat)
     ::error          - Error message if failed

   Example:
     (interrupt! {::ai/session-id \"a1b2\"})
     ;; => {:seon.ai/session-id \"a1b2\"
     ;;     :seon.ai.claude/interrupted? true
     ;;     :seon.ai.claude/method :destroy}

   Note: For provider-agnostic interruption, use seon.ai.agent/interrupt! instead."
  [{::ai/keys [session-id]}]
  (let [result (agent/interrupt! {::agent/session-id session-id})]
    ;; Map to Claude-specific response format for backwards compatibility
    (cond-> {::ai/session-id session-id
             ::interrupted? (::agent/interrupted? result)}
      (::agent/method result)
      (assoc ::method (if (= :close (::agent/method result))
                        :destroy  ; backwards compat: close -> destroy
                        (::agent/method result)))
      (::agent/error result)
      (assoc ::error (::agent/error result)))))

(defn tail
  "Stream messages from a Claude agent session.

   Delegates to seon.ai.agent/tail.

   Request keys:
     ::ai/session-id - Required. The 4-char hex session ID

   Returns:
     A core.async channel of SDK messages, or nil if agent not found.

   Example:
     (let [ch (tail {::ai/session-id \"a1b2\"})]
       (go-loop []
         (when-let [msg (<! ch)]
           (println \"Agent says:\" (:type msg))
           (recur))))

   Note: For provider-agnostic tail, use seon.ai.agent/tail instead."
  [{::ai/keys [session-id]}]
  (agent/tail {::agent/session-id session-id}))

(defn get-agent
  "Get a Claude agent handle by session ID.

   Delegates to seon.ai.agent/get-agent.

   Request keys:
     ::ai/session-id - Required. The 4-char hex session ID

   Returns:
     The full agent handle map, or nil if not found.

   Note: For provider-agnostic lookup, use seon.ai.agent/get-agent instead."
  [{::ai/keys [session-id]}]
  (agent/get-agent {::agent/session-id session-id}))

(comment
  ;; REPL exploration

  ;; View registered schemas
  (require '[seon.schema :as schema])
  (schema/schemas-in-namespace "seon.ai.claude")

  ;; Generate sample data
  (require '[malli.generator :as mg])
  (mg/generate ::model)
  (mg/generate ::message-type)
  (mg/generate ::agent-status)

  ;; Validate data
  (require '[malli.core :as m])
  (m/validate ::model "claude-opus-4-5-20251101")
  (m/validate ::message-type "assistant")

  ;; Test sdk-message->entity (uses map-in pattern)
  (sdk-message->entity {::sdk-message {:type "assistant"
                                        :uuid "msg-123"
                                        :message {:role "assistant"
                                                  :content [{:type "text" :text "Hello!"}]}}})

  nil)
