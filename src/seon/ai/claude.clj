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
   [cheshire.core :as json]
   [clojure.core.async :as async :refer [chan close! go-loop]]
   [clojure.java.io :as io]
   [clojure.java.process :as process]
   [clojure.string :as str]
   [seon.ai :as ai]
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

;; Permission modes for tool execution
(schema/register! ::permission-mode
                  [:enum "default" "acceptEdits" "bypassPermissions" "plan" "dontAsk"])

;; CLI configuration
(schema/register! ::cli-command
                  [:string {:min 1}])

(schema/register! ::cwd
                  [:string {:min 1}])

(schema/register! ::max-turns
                  [:int {:min 1 :max 1000}])

(schema/register! ::max-budget-usd
                  [:double {:min 0.0 :max 100.0}])

(schema/register! ::allowed-tools
                  [:vector :string])

(schema/register! ::disallowed-tools
                  [:vector :string])

(schema/register! ::mcp-servers
                  [:map-of :keyword :map])

(schema/register! ::settings-path
                  [:string {:min 1
                            :description "Path to settings JSON file for hooks"}])

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
(schema/register! ::launch-agent-request
                  [:map
                   [::ai/node ::ai/node]
                   [::ai/namespace ::ai/namespace]
                   [::ai/prompt ::ai/prompt]
                   [::model {:optional true} ::model]
                   [::permission-mode {:optional true} ::permission-mode]
                   [::max-turns {:optional true} ::max-turns]
                   [::max-budget-usd {:optional true} ::max-budget-usd]
                   [::allowed-tools {:optional true} ::allowed-tools]
                   [::disallowed-tools {:optional true} ::disallowed-tools]])

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
;;; Configuration
;;; ---------------------------------------------------------------------------

(def ^:const default-cli-command
  "Default Claude Code command (Homebrew npm install location)."
  "/opt/homebrew/bin/claude")

(def ^:const default-model
  "Default model. Opus 4.5 for complex tasks."
  "claude-opus-4-5-20251101")

(def ^:const default-permission-mode
  "Default permission mode."
  "default")

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
;;; Private Implementation (moved from seon.claude.sdk)
;;; ---------------------------------------------------------------------------

(defn- build-args
  "Build CLI arguments from options map."
  [{::keys [model permission-mode max-turns max-budget-usd
            allowed-tools disallowed-tools mcp-servers cli-command settings-path]}]
  (let [cmd (or cli-command default-cli-command)
        model (or model default-model)
        perm (or permission-mode default-permission-mode)
        settings (or settings-path ".claude/settings.json")]
    (cond-> [cmd
             "--output-format" "stream-json"
             "--input-format" "stream-json"
             "--verbose"
             "--model" model
             "--permission-mode" perm
             "--setting-sources" "project,local"
             "--settings" settings]
      max-turns
      (into ["--max-turns" (str max-turns)])

      max-budget-usd
      (into ["--max-budget-usd" (str max-budget-usd)])

      (seq allowed-tools)
      (into ["--allowedTools" (str/join "," allowed-tools)])

      (seq disallowed-tools)
      (into ["--disallowedTools" (str/join "," disallowed-tools)])

      (seq mcp-servers)
      (into ["--mcp-config" (json/generate-string {:mcpServers mcp-servers})]))))

(defn- build-env
  "Build environment map with SDK identifier."
  []
  (-> (into {} (System/getenv))
      (assoc "ANTHROPIC_API_KEY" "")
      (assoc "CLAUDE_USE_SUBSCRIPTION" "true")
      (assoc "CLAUDE_CODE_ENTRYPOINT" "sdk-clj")))

(defn- make-user-message
  "Create a user message for the Claude Code CLI."
  [text]
  {:type "user"
   :session_id ""
   :message {:role "user"
             :content [{:type "text" :text text}]}
   :parent_tool_use_id nil})

(defn- write-message!
  "Write a JSON message to the process stdin."
  [^java.io.OutputStream stdin msg]
  (let [json-str (str (json/generate-string msg) "\n")
        bytes (.getBytes json-str "UTF-8")]
    (.write stdin bytes)
    (.flush stdin)))

(defn- parse-line
  "Parse a JSON line from stdout, returning error map on failure."
  [line]
  (try
    (json/parse-string line true)
    (catch Exception e
      {:type "parse_error" :raw line :error (str e)})))

(defn- spawn-claude-code
  "Spawn Claude Code CLI process."
  [{::keys [cwd] :as opts}]
  (let [args (build-args opts)
        env (build-env)
        dir (or cwd ".")
        _ (log/debug "Spawning Claude Code" {:args args :cwd dir})
        proc (apply process/start {:dir dir :env env} args)]
    {:process proc
     :stdin (process/stdin proc)
     :stdout (process/stdout proc)
     :stderr (process/stderr proc)
     :exit-ref (process/exit-ref proc)}))

(defn- build-agent-mcp-config
  "Build MCP config that passes session_id to the agent."
  [session-id]
  {:seon {:command "./bin/mcp-server"
          :env {"SEON_SESSION_ID" session-id}}})

(defn- build-agent-prompt
  "Build the agent prompt with session context."
  [session-id namespace prompt]
  (str "You have been assigned session ID: " session-id "\n"
       "Namespace: " namespace "\n\n"
       "To evaluate Clojure code, use the eval tool:\n\n"
       "  eval(session_id=\"" session-id "\", code=\"(your-code-here)\")\n\n"
       "Your context atom `*ctx*` is available. Use namespaced keys:\n\n"
       "  eval(session_id=\"" session-id "\", code=\"(swap! *ctx* assoc :" namespace "/signals [...])\")\n"
       "  eval(session_id=\"" session-id "\", code=\"(:" namespace "/signals @*ctx*)\")\n\n"
       "Helper functions from user namespace (qualify with user/):\n\n"
       "  eval(session_id=\"" session-id "\", code=\"(user/reload)\")           ; Reload changed code\n"
       "  eval(session_id=\"" session-id "\", code=\"(user/search \\\"query\\\")\")  ; Web search via Gemini\n"
       "  eval(session_id=\"" session-id "\", code=\"(user/status)\")           ; System status\n\n"
       "All state is automatically persisted. You don't need to save anything manually.\n"
       "Each eval response includes the current namespace (;; ns: " namespace ").\n\n"
       "---\n\n"
       "TASK:\n" prompt))

;;; ---------------------------------------------------------------------------
;;; Agent Registry
;;; ---------------------------------------------------------------------------

(defonce ^:private agent-registry (atom {}))

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
     ::ai/node            - Required. XTDB orchestrator node
     ::ai/namespace       - Required. Agent namespace (string or symbol)
     ::ai/prompt          - Required. Task description for the agent
     ::model              - Optional. Claude model (default: opus)
     ::permission-mode    - Optional. Permission mode (default: bypassPermissions)
     ::max-turns          - Optional. Max conversation turns
     ::max-budget-usd     - Optional. Cost limit
     ::allowed-tools      - Optional. Tool whitelist
     ::disallowed-tools   - Optional. Tool denylist

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
  [{::ai/keys [node namespace prompt]
    ::keys [model permission-mode max-turns max-budget-usd allowed-tools disallowed-tools]}]
  (log/info "Launching agent" {:namespace namespace})

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
    (let [{ai-session-id ::ai/session-id}
          (ai/start-session! {::ai/node node
                              ::ai/namespace namespace
                              ::ai/prompt prompt})
          _ (log/info "Created AI session for persistence" {:ai-session-id ai-session-id})

          ;; 3. Build agent prompt with session context
          full-prompt (build-agent-prompt id namespace prompt)

          ;; 4. Build MCP config with session_id in environment
          mcp-config (build-agent-mcp-config id)

          ;; 5. Spawn Claude Code with configured MCP
          status-atom (atom :running)
          {:keys [process stdin stdout]}
          (spawn-claude-code {::model (or model default-model)
                              ::permission-mode (or permission-mode "bypassPermissions")
                              ::max-turns max-turns
                              ::max-budget-usd max-budget-usd
                              ::allowed-tools allowed-tools
                              ::disallowed-tools disallowed-tools
                              ::mcp-servers mcp-config})

          messages-ch (chan 100)
          result-ch (chan 1)

          ;; 6. Start reader that persists messages and updates status
          claude-session-mapped? (atom false)
          _reader (future
                    (try
                      (with-open [rdr (io/reader stdout)]
                        (loop []
                          (when-let [line (.readLine rdr)]
                            (when-not (str/blank? line)
                              (let [msg (parse-line line)
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
                        (close! messages-ch)
                        (close! result-ch))))

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
                     ;; Remove from registry
                     (swap! agent-registry dissoc id)
                     ;; Update status
                     (when (= :running @status-atom)
                       (reset! status-atom :terminated)))

          handle {::ai/session-id id
                  ::ai-session-id ai-session-id
                  ::ai/namespace (str namespace)
                  ::nrepl-port nrepl-port
                  ::messages-ch messages-ch
                  ::result-ch result-ch
                  ::status-atom status-atom
                  ::close! close-fn}]

      ;; 8. Send initial prompt
      (write-message! stdin (make-user-message full-prompt))

      ;; 9. Register agent
      (swap! agent-registry assoc id handle)

      (log/info "Agent launched" {:session-id id
                                  :ai-session-id ai-session-id
                                  :namespace namespace
                                  :port nrepl-port})

      handle)))

(defn agents
  "List all running agents with status, namespace, session-id.

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
     ;;      :seon.ai.claude/agent-status :running}]"
  [_request]
  (vec (for [[id handle] @agent-registry]
         {::ai/session-id id
          ::ai/namespace (::ai/namespace handle)
          ::nrepl-port (::nrepl-port handle)
          ::agent-status @(::status-atom handle)})))

(defn interrupt!
  "Send interrupt signal to an agent.

   Attempts to interrupt the agent's Claude process by destroying it.
   This is the only reliable way to stop a running agent since the
   Claude CLI does not accept interrupt messages over stdin.

   Request keys:
     ::ai/session-id - Required. The 4-char hex session ID

   Response keys:
     ::ai/session-id  - The session that was interrupted
     ::interrupted?   - Whether the interrupt succeeded
     ::method         - Method used (:destroy)
     ::error          - Error message if failed

   Example:
     (interrupt! {::ai/session-id \"a1b2\"})
     ;; => {:seon.ai/session-id \"a1b2\"
     ;;     :seon.ai.claude/interrupted? true
     ;;     :seon.ai.claude/method :destroy}"
  [{::ai/keys [session-id]}]
  (if-let [handle (get @agent-registry session-id)]
    (let [status-atom (::status-atom handle)
          close! (::close! handle)]
      (try
        (close!)
        (reset! status-atom :interrupted)
        (log/info "Interrupted agent" {:session-id session-id})
        {::ai/session-id session-id
         ::interrupted? true
         ::method :destroy}
        (catch Exception e
          (log/warn e "Failed to interrupt agent" {:session-id session-id})
          {::ai/session-id session-id
           ::interrupted? false
           ::error (.getMessage e)})))
    ;; Agent not found
    {::ai/session-id session-id
     ::interrupted? false
     ::error "Agent not found in registry"}))

(defn tail
  "Stream messages from a specific agent session.

   Returns the agent's messages channel. Use this to observe an agent's
   activity in real-time.

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

   Note: Close the returned channel when done observing to release resources."
  [{::ai/keys [session-id]}]
  (when-let [handle (get @agent-registry session-id)]
    (::messages-ch handle)))

(defn get-agent
  "Get an agent handle by session ID.

   Request keys:
     ::ai/session-id - Required. The 4-char hex session ID

   Returns:
     The full agent handle map, or nil if not found."
  [{::ai/keys [session-id]}]
  (get @agent-registry session-id))

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
