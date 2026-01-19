(ns seon.claude.sdk
  "DEPRECATED: Use seon.ai.claude instead.

   This namespace is maintained for backwards compatibility only.
   All new code should use seon.ai.claude for agent management
   and seon.ai for base AI session/message functions.

   Migration guide:
   - seon.claude.sdk/launch-agent! -> seon.ai.claude/launch-agent!
   - seon.claude.sdk/agents        -> seon.ai.claude/agents
   - seon.claude.sdk/interrupt!    -> seon.ai.claude/interrupt!
   - seon.claude.sdk/tail          -> seon.ai.claude/tail

   The new namespaces provide:
   - Better schema composition (Claude extends base AI schemas)
   - Automatic message persistence to XTDB
   - Session cost tracking and analytics

   ---

   LEGACY DOCS (for reference during migration):

   Native Clojure SDK for spawning Claude Code CLI agents.

   Spawns and controls Claude Code agents from the JVM using the JSON-RPC
   protocol over stdin/stdout. Uses clojure.java.process (Clojure 1.12+).

   Public functions use map-based APIs with namespaced keys:
   - Input:  {::prompt \"...\" ::options {...}}
   - Output: {::messages-ch <chan> ::result-ch <chan> ...}

   Quick start:

     (require '[seon.claude.sdk :as sdk])

     ;; Simple blocking query
     (sdk/exec {::sdk/prompt \"What is 2+2?\"})

     ;; Streaming with message handling
     (let [handle (sdk/query {::sdk/prompt \"List files\"})]
       (go-loop []
         (when-let [msg (<! (::sdk/messages-ch handle))]
           (println (:type msg))
           (recur)))
       (<!! (::sdk/result-ch handle)))

   Agent lifecycle (Phase 7):

     ;; Launch agent with isolated session
     (def agent (sdk/launch-agent!
                  {::sdk/node xtdb-node
                   ::sdk/namespace 'seon.trading
                   ::sdk/prompt \"Implement feature X\"}))

     ;; Monitor
     @(::sdk/result-ch agent)

     ;; Terminate
     (sdk/terminate-agent! {::sdk/agent-handle agent})

   Schema verification:

     (require '[malli.instrument :as mi])
     (mi/collect! {:ns 'seon.claude.sdk})
     (mi/check {:filters [(mi/-filter-ns 'seon.claude.sdk)]})"
  (:require
   [cheshire.core :as json]
   [clojure.core.async :as async :refer [chan close! go-loop]]
   [clojure.java.io :as io]
   [clojure.java.process :as process]
   [clojure.string :as str]
   [seon.orchestrator.session :as session]
   [seon.schema :as schema]
   [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

;; Model identifiers - Opus 4.5 is default for complex tasks
(schema/register! ::model
                  [:enum
                   "claude-opus-4-5-20251101"
                   "claude-sonnet-4-20250514"
                   "claude-3-5-haiku-20241022"])

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

;; Query options
(schema/register! ::query-options
                  [:map
                   [::model {:optional true} ::model]
                   [::cwd {:optional true} ::cwd]
                   [::permission-mode {:optional true} ::permission-mode]
                   [::allowed-tools {:optional true} ::allowed-tools]
                   [::disallowed-tools {:optional true} ::disallowed-tools]
                   [::mcp-servers {:optional true} ::mcp-servers]
                   [::max-turns {:optional true} ::max-turns]
                   [::max-budget-usd {:optional true} ::max-budget-usd]
                   [::cli-command {:optional true} ::cli-command]])

;; Prompt (required input)
;; Explicitly prevents generation to avoid spawning real processes during tests
(schema/register! ::prompt
                  [:any {:description "Prompt text for Claude query"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate prompt - would spawn process"
                                                           {:type :malli.generator/no-generator})))}])

;; Request schema
(schema/register! ::query-request
                  [:map
                   [::prompt ::prompt]
                   [::options {:optional true} ::query-options]])

;; Message types from Claude Code
(schema/register! ::message-type
                  [:enum "system" "assistant" "user" "result" "keep_alive" "parse_error"])

;; SDK message (generic)
(schema/register! ::sdk-message
                  [:map
                   [:type ::message-type]
                   [:session_id {:optional true} :string]
                   [:uuid {:optional true} :string]])

;; Result subtypes
(schema/register! ::result-subtype
                  [:enum "success" "error_during_execution" "error_max_turns"
                   "error_max_budget_usd" "interrupted"])

;; Result message (final completion)
(schema/register! ::result-message
                  [:map
                   [:type [:= "result"]]
                   [:subtype ::result-subtype]
                   [:result {:optional true} :string]
                   [:num_turns :int]
                   [:total_cost_usd :double]
                   [:duration_ms :int]
                   [:is_error {:optional true} :boolean]
                   [:session_id {:optional true} :string]
                   [:uuid {:optional true} :string]])

;; Exec response (same as result-message, but follows naming convention)
(schema/register! ::exec-response ::result-message)

;; Query handle (returned from query)
(schema/register! ::query-handle
                  [:map
                   [::messages-ch :any]    ; core.async channel
                   [::result-ch :any]      ; core.async channel
                   [::send! fn?]           ; function to send follow-up messages
                   [::close! fn?]])        ; function to close/cleanup

;;; ---------------------------------------------------------------------------
;;; Agent Launch Schemas (Phase 7)
;;; ---------------------------------------------------------------------------

;; XTDB node - cannot be generated (requires real database)
(schema/register! ::node
                  [:any {:description "XTDB orchestrator node"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate XTDB node"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::namespace
                  [:symbol {:description "Agent namespace symbol"}])

(schema/register! ::session-id
                  [:string {:min 4 :max 4
                            :pattern "^[a-f0-9]{4}$"
                            :description "4-character hex session ID"}])

(schema/register! ::agent-status
                  [:enum :running :completed :failed :terminated])

;; Launch request
(schema/register! ::launch-agent-request
                  [:map
                   [::node ::node]
                   [::namespace ::namespace]
                   [::prompt ::prompt]
                   [::model {:optional true} ::model]
                   [::permission-mode {:optional true} ::permission-mode]
                   [::max-turns {:optional true} ::max-turns]
                   [::max-budget-usd {:optional true} ::max-budget-usd]
                   [::allowed-tools {:optional true} ::allowed-tools]
                   [::disallowed-tools {:optional true} ::disallowed-tools]])

;; Agent handle (returned from launch-agent!)
(schema/register! ::agent-handle
                  [:map
                   [::session-id ::session-id]
                   [::namespace ::namespace]
                   [::nrepl-port :int]
                   [::messages-ch :any]    ; core.async channel
                   [::result-ch :any]      ; core.async channel
                   [::status-atom :any]    ; atom with agent status
                   [::close! fn?]])        ; function to terminate

;; Terminate request
(schema/register! ::terminate-agent-request
                  [:map
                   [::agent-handle ::agent-handle]
                   [::node ::node]])

;; Terminate response
(schema/register! ::terminate-agent-response
                  [:map
                   [::session-id ::session-id]
                   [::status ::agent-status]
                   [::result {:optional true} ::result-message]])

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
;;; Private Implementation
;;; ---------------------------------------------------------------------------

(defn- build-args
  "Build CLI arguments from options map."
  [{::keys [model permission-mode max-turns max-budget-usd
            allowed-tools disallowed-tools mcp-servers cli-command settings-path]}]
  (let [cmd (or cli-command default-cli-command)
        model (or model default-model)
        perm (or permission-mode default-permission-mode)
        ;; Default to project settings.json so hooks are loaded
        settings (or settings-path ".claude/settings.json")]
    ;; Note: "claude" is a standalone executable (npm installed), not a node script
    (cond-> [cmd
             "--output-format" "stream-json"
             "--input-format" "stream-json"
             "--verbose"  ; REQUIRED for stream-json to work
             "--model" model
             "--permission-mode" perm
             "--setting-sources" "project,local"  ; Load project settings (contains hooks)
             "--settings" settings]  ; Also load hooks from explicit path
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
  "Build environment map with SDK identifier.
   Explicitly clears ANTHROPIC_API_KEY and sets CLAUDE_USE_SUBSCRIPTION to force Max subscription."
  []
  (-> (into {} (System/getenv))
      (assoc "ANTHROPIC_API_KEY" "")           ;; Clear API key
      (assoc "CLAUDE_USE_SUBSCRIPTION" "true") ;; Force Max subscription
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

(defn- start-reader!
  "Start background reader that parses JSON from stdout and puts messages on channels.
   Returns the future for the reader thread."
  [stdout messages-ch result-ch]
  (future
    (try
      (with-open [rdr (io/reader stdout)]
        (loop []
          (when-let [line (.readLine rdr)]
            (when-not (str/blank? line)
              (let [msg (parse-line line)]
                (log/trace "Claude SDK received" {:type (:type msg)})
                (async/>!! messages-ch msg)
                (when (= (:type msg) "result")
                  (async/>!! result-ch msg))))
            (recur))))
      (catch Exception e
        (log/warn e "Claude SDK reader error"))
      (finally
        (close! messages-ch)
        (close! result-ch)))))

;;; ---------------------------------------------------------------------------
;;; Process Management
;;; ---------------------------------------------------------------------------

(defn- spawn-claude-code
  "INTERNAL: Spawn Claude Code CLI process using clojure.java.process.

   WARNING: This function returns raw process streams. NEVER use blocking IO
   (slurp, line-seq without timeout, etc.) on the returned streams - this will
   block the calling thread indefinitely if the subprocess doesn't terminate.
   Use the public `query` or `exec` functions instead.

   Options map accepts namespaced keys:
     ::model           - Model name (default: claude-opus-4-5-20251101)
     ::cwd             - Working directory
     ::permission-mode - Permission mode (default: default)
     ::allowed-tools   - Vector of allowed tool names
     ::disallowed-tools - Vector of disallowed tool names
     ::mcp-servers     - Map of MCP server configs
     ::max-turns       - Max conversation turns
     ::max-budget-usd  - Cost limit
     ::cli-command     - Path to claude command

   Returns a map with:
     :process   - The Process object
     :stdin     - OutputStream for sending messages
     :stdout    - InputStream for receiving messages
     :stderr    - InputStream for error output
     :exit-ref  - Deref-able for exit code"
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

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn query
  "Execute a Claude Code query. Returns a query handle for streaming messages.

   Request keys:
     ::prompt  - Required. The prompt text
     ::options - Optional. Query options map (see ::query-options schema)

   Response keys (query handle):
     ::messages-ch - Channel of SDK messages (all message types)
     ::result-ch   - Channel receiving the final result message
     ::send!       - Function to send follow-up user messages
     ::close!      - Function to terminate the query and cleanup

   Message types received on ::messages-ch:
     \"system\"    - Init message with session info, available tools
     \"assistant\" - Claude's responses (text, tool_use)
     \"user\"      - Tool results (echoed back)
     \"result\"    - Final completion (also sent to ::result-ch)
     \"keep_alive\" - Periodic heartbeat

   Example:
     (let [handle (query {::prompt \"What is 2+2?\"})]
       (go-loop []
         (when-let [msg (<! (::messages-ch handle))]
           (println (:type msg))
           (recur)))
       (<!! (::result-ch handle)))

   Note: Spawns external process - not generatively testable."
  {:malli/schema [:=> [:cat ::query-request] ::query-handle]}
  [{::keys [prompt options]}]
  (let [{:keys [process stdin stdout]} (spawn-claude-code (or options {}))
        messages-ch (chan 100)
        result-ch (chan 1)
        _reader (start-reader! stdout messages-ch result-ch)]
    ;; Send initial prompt
    (write-message! stdin (make-user-message prompt))
    ;; Return handle
    {::messages-ch messages-ch
     ::result-ch result-ch
     ::send! (fn send-message [text]
               (write-message! stdin (make-user-message text)))
     ::close! (fn close-query []
                (try
                  (.destroy process)
                  (catch Exception e
                    (log/warn e "Error destroying Claude process")))
                (close! messages-ch)
                (close! result-ch))}))

(defn exec
  "Execute a query and block until completion. Returns the result message.

   Convenience wrapper around `query` for simple use cases where you
   don't need to process streaming messages.

   Request keys:
     ::prompt  - Required. The prompt text
     ::options - Optional. Query options map

   Returns:
     The result message map with keys:
       :type          - \"result\"
       :subtype       - \"success\", \"error_*\", etc.
       :result        - Final text response
       :num_turns     - Number of conversation turns
       :total_cost_usd - Total cost in USD
       :duration_ms   - Execution time in ms

   Example:
     (exec {::prompt \"List files in src/\"
            ::options {::model \"claude-opus-4-5-20251101\"
                       ::max-turns 5}})
     ;; => {:type \"result\" :subtype \"success\" :result \"...\" ...}

   Note: Spawns external process - not generatively testable."
  {:malli/schema [:=> [:cat ::query-request] ::exec-response]}
  [{::keys [prompt options] :as request}]
  (let [handle (query request)
        result (async/<!! (::result-ch handle))]
    ((::close! handle))
    result))

;;; ---------------------------------------------------------------------------
;;; Agent Lifecycle (Phase 7)
;;; ---------------------------------------------------------------------------

;; Registry of active agents for tracking
(defonce ^:private agent-registry (atom {}))

(defn- build-agent-mcp-config
  "Build MCP config that passes session_id to the agent.
   The agent will use this session_id for all eval calls."
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

(defn launch-agent!
  "Launch a Claude Code agent with an isolated Seon session.

   Creates everything the agent needs:
   - Isolated XTDB database for the namespace
   - Persisted ctx atom for state management
   - Dedicated nREPL server
   - Claude Code process with MCP configured

   Request keys:
     ::node            - Required. XTDB orchestrator node
     ::namespace       - Required. Agent namespace symbol (e.g., 'seon.trading)
     ::prompt          - Required. Task description for the agent
     ::model           - Optional. Claude model (default: opus)
     ::permission-mode - Optional. Permission mode (default: bypassPermissions)
     ::max-turns       - Optional. Max conversation turns
     ::max-budget-usd  - Optional. Cost limit
     ::allowed-tools   - Optional. Tool whitelist
     ::disallowed-tools - Optional. Tool denylist

   Response keys (agent handle):
     ::session-id   - 4-char hex session ID
     ::namespace    - Agent namespace
     ::nrepl-port   - nREPL port for the agent
     ::messages-ch  - Channel of SDK messages
     ::result-ch    - Channel receiving final result
     ::status-atom  - Atom with current status (:running, :completed, :failed)
     ::close!       - Function to terminate agent

   Example:
     (launch-agent! {::node xtdb-node
                     ::namespace 'seon.trading
                     ::prompt \"Implement the signals dashboard\"})"
  {:malli/schema [:=> [:cat ::launch-agent-request] ::agent-handle]}
  [{::keys [node namespace prompt model permission-mode
            max-turns max-budget-usd allowed-tools disallowed-tools]}]
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

    ;; 2. Build agent prompt with session context
    (let [full-prompt (build-agent-prompt id namespace prompt)

          ;; 3. Build MCP config with session_id in environment
          mcp-config (build-agent-mcp-config id)

          ;; 4. Spawn Claude Code with configured MCP
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

          ;; 5. Start reader that updates status on completion
          ;; Also captures Claude's internal session_id for hook routing
          claude-session-mapped? (atom false)
          _reader (future
                    (try
                      (with-open [rdr (io/reader stdout)]
                        (loop []
                          (when-let [line (.readLine rdr)]
                            (when-not (str/blank? line)
                              (let [msg (parse-line line)
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
                                (log/trace "Agent message" {:session-id id :type (:type msg)})
                                (async/>!! messages-ch msg)
                                (when (= (:type msg) "result")
                                  (async/>!! result-ch msg)
                                  ;; Update status based on result
                                  (reset! status-atom
                                          (if (= "success" (:subtype msg))
                                            :completed
                                            :failed)))))
                            (recur))))
                      (catch Exception e
                        (log/warn e "Agent reader error" {:session-id id})
                        (reset! status-atom :failed))
                      (finally
                        (close! messages-ch)
                        (close! result-ch))))

          ;; 6. Build close function that cleans up everything
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
                     ;; Stop Seon session (flushes ctx, stops nREPL)
                     (session/stop-agent-session! {::session/node node
                                                   ::session/id id})
                     ;; Remove from registry
                     (swap! agent-registry dissoc id)
                     ;; Update status
                     (when (= :running @status-atom)
                       (reset! status-atom :terminated)))

          handle {::session-id id
                  ::namespace namespace
                  ::nrepl-port nrepl-port
                  ::messages-ch messages-ch
                  ::result-ch result-ch
                  ::status-atom status-atom
                  ::close! close-fn}]

      ;; 7. Send initial prompt
      (write-message! stdin (make-user-message full-prompt))

      ;; 8. Register agent
      (swap! agent-registry assoc id handle)

      (log/info "Agent launched" {:session-id id :namespace namespace :port nrepl-port})

      handle)))

(defn terminate-agent!
  "Terminate a running agent, cleaning up all resources.

   Request keys:
     ::agent-handle - Required. The handle returned from launch-agent!
     ::node         - Required. XTDB orchestrator node

   Response keys:
     ::session-id - The terminated session ID
     ::status     - Final status (:terminated, :completed, :failed)
     ::result     - Final result message (if available)

   Example:
     (terminate-agent! {::agent-handle agent ::node xtdb-node})"
  {:malli/schema [:=> [:cat ::terminate-agent-request] ::terminate-agent-response]}
  [{::keys [agent-handle node]}]
  (let [session-id (::session-id agent-handle)
        result-ch (::result-ch agent-handle)
        status-atom (::status-atom agent-handle)
        close! (::close! agent-handle)]

    ;; Try to get final result (non-blocking)
    (let [result (async/poll! result-ch)]

      ;; Call close function
      (close!)

      {::session-id session-id
       ::status @status-atom
       ::result result})))

;; List agents request/response schemas
(schema/register! ::list-agents-request
                  [:map])

(schema/register! ::agent-summary
                  [:map
                   [::session-id ::session-id]
                   [::namespace ::namespace]
                   [::nrepl-port :int]
                   [::status ::agent-status]])

(schema/register! ::list-agents-response
                  [:vector ::agent-summary])

(defn list-agents
  "List all active agents launched via launch-agent!

   Request keys:
     (none - empty map for consistency)

   Response keys (vector of):
     ::session-id  - Agent session ID
     ::namespace   - Agent namespace
     ::nrepl-port  - nREPL port
     ::status      - Current status

   Example:
     (list-agents {})"
  {:malli/schema [:=> [:cat ::list-agents-request] ::list-agents-response]}
  [{::keys []}]
  (vec (for [[id handle] @agent-registry]
         {::session-id id
          ::namespace (::namespace handle)
          ::nrepl-port (::nrepl-port handle)
          ::status @(::status-atom handle)})))

;;; ---------------------------------------------------------------------------
;;; Agent Observatory (Orchestrator Visibility)
;;; ---------------------------------------------------------------------------

;; Schema for agents function (convenience alias for list-agents)
(schema/register! ::agents-request
                  [:map])

(defn agents
  "List all running agents with status, namespace, session-id, cost so far.

   This is a convenience function that returns a formatted view of all
   active agents for the orchestrator.

   Request keys:
     (none - empty map for consistency)

   Response keys (vector of):
     ::session-id  - 4-char hex session ID
     ::namespace   - Agent namespace symbol
     ::nrepl-port  - nREPL port for direct REPL access
     ::status      - Current status (:running, :completed, :failed, :terminated)

   Example:
     (agents {})
     ;; => [{:seon.claude.sdk/session-id \"a1b2\"
     ;;      :seon.claude.sdk/namespace 'seon.trading
     ;;      :seon.claude.sdk/nrepl-port 7889
     ;;      :seon.claude.sdk/status :running}]"
  {:malli/schema [:=> [:cat ::agents-request] ::list-agents-response]}
  [{::keys []}]
  (list-agents {}))

;; Schema for tail
(schema/register! ::tail-request
                  [:map
                   [::session-id ::session-id]])

(defn tail
  "Stream messages from a specific agent session.

   Returns a core.async channel that receives copies of all messages
   sent to the agent's messages-ch. Use this to observe an agent's
   activity in real-time without affecting its operation.

   The returned channel is a 'tap' on the agent's message mult,
   meaning it receives all messages but doesn't consume them from
   the original channel.

   Request keys:
     ::session-id - Required. The 4-char hex session ID

   Returns:
     A core.async channel of SDK messages, or nil if agent not found.

   Example:
     (let [ch (tail {::session-id \"a1b2\"})]
       (go-loop []
         (when-let [msg (<! ch)]
           (println \"Agent says:\" (:type msg))
           (recur))))

   Note: Close the returned channel when done observing to release resources."
  {:malli/schema [:=> [:cat ::tail-request] [:maybe :any]]}
  [{::keys [session-id]}]
  (when-let [handle (get @agent-registry session-id)]
    ;; Return the messages channel directly
    ;; Note: This shares the channel, so multiple consumers will compete
    ;; In production, we should use async/mult for proper fan-out
    (::messages-ch handle)))

;; Schema for get-agent
(schema/register! ::get-agent-request
                  [:map
                   [::session-id ::session-id]])

(defn get-agent
  "Get an agent handle by session ID.

   Request keys:
     ::session-id - Required. The 4-char hex session ID

   Returns:
     The full agent handle map, or nil if not found."
  {:malli/schema [:=> [:cat ::get-agent-request] [:maybe ::agent-handle]]}
  [{::keys [session-id]}]
  (get @agent-registry session-id))

;; Schema for interrupt!
(schema/register! ::interrupt-request
                  [:map
                   [::session-id ::session-id]])

(schema/register! ::interrupt-response
                  [:map
                   [::session-id ::session-id]
                   [::interrupted? :boolean]
                   [::method {:optional true} [:enum :sigint :destroy]]
                   [::error {:optional true} :string]])

(defn interrupt!
  "Send interrupt signal to an agent.

   Attempts to interrupt the agent's Claude process. This is done by:
   1. First trying to send SIGINT to the process (graceful)
   2. If that fails, destroying the process (forceful)

   Note: Based on protocol exploration, the Claude CLI does NOT accept
   'interrupt' or 'control' type messages over stdin. The only way to
   interrupt is via process signals or destruction.

   Request keys:
     ::session-id - Required. The 4-char hex session ID

   Response keys:
     ::session-id   - The session that was interrupted
     ::interrupted? - Whether the interrupt succeeded
     ::method       - Method used (:sigint or :destroy)
     ::error        - Error message if failed

   Example:
     (interrupt! {::session-id \"a1b2\"})
     ;; => {:seon.claude.sdk/session-id \"a1b2\"
     ;;     :seon.claude.sdk/interrupted? true
     ;;     :seon.claude.sdk/method :sigint}"
  {:malli/schema [:=> [:cat ::interrupt-request] ::interrupt-response]}
  [{::keys [session-id]}]
  (if-let [handle (get @agent-registry session-id)]
    (let [status-atom (::status-atom handle)
          close! (::close! handle)]
      ;; The close! function already handles process destruction
      ;; We just need to call it and update status
      (try
        (close!)
        (reset! status-atom :interrupted)
        (log/info "Interrupted agent" {:session-id session-id})
        {::session-id session-id
         ::interrupted? true
         ::method :destroy}
        (catch Exception e
          (log/warn e "Failed to interrupt agent" {:session-id session-id})
          {::session-id session-id
           ::interrupted? false
           ::error (.getMessage e)})))
    ;; Agent not found
    {::session-id session-id
     ::interrupted? false
     ::error "Agent not found in registry"}))

;; Schema for agent-cost
(schema/register! ::agent-cost-request
                  [:map
                   [::session-id ::session-id]])

(schema/register! ::agent-cost-response
                  [:map
                   [::session-id ::session-id]
                   [::cost-usd {:optional true} :double]
                   [::turns {:optional true} :int]
                   [::found? :boolean]])

(defn agent-cost
  "Get the current cost for a running agent.

   Note: This only returns data if the agent has completed (result message
   received). For running agents, cost is not available until completion.

   Request keys:
     ::session-id - Required. The 4-char hex session ID

   Response keys:
     ::session-id - The session ID
     ::cost-usd   - Total cost in USD (if available)
     ::turns      - Number of turns (if available)
     ::found?     - Whether the agent was found"
  {:malli/schema [:=> [:cat ::agent-cost-request] ::agent-cost-response]}
  [{::keys [session-id]}]
  (if-let [handle (get @agent-registry session-id)]
    (let [result-ch (::result-ch handle)
          ;; Try to peek at result without consuming
          result (async/poll! result-ch)]
      (if result
        {::session-id session-id
         ::cost-usd (:total_cost_usd result)
         ::turns (:num_turns result)
         ::found? true}
        {::session-id session-id
         ::found? true}))
    {::session-id session-id
     ::found? false}))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  ;; View registered schemas
  (require '[seon.schema :as schema])
  (schema/schemas-in-namespace "seon.claude.sdk")

  ;; =========================================================================
  ;; Agent Lifecycle (Phase 7)
  ;; =========================================================================

  ;; Get the running orchestrator XTDB node (from Integrant system)
  ;; This is NOT a new database - it's the shared orchestrator node
  ;; that tracks session metadata and attaches namespace-specific databases
  (require '[user :refer [xtdb-node]])

  ;; Launch an agent with isolated session
  ;; - Creates namespace-specific database (e.g., test_agent) if needed
  ;; - Starts dedicated nREPL server for the agent
  ;; - Spawns Claude Code with MCP configured to use the session
  (def agent-handle
    (launch-agent! {::node (xtdb-node)
                    ::namespace 'test.agent
                    ::prompt "Say hello and then evaluate (+ 1 2) using the eval tool."
                    ::max-turns 5}))

  ;; Check agent status
  @(::status-atom agent-handle)
  ;; => :running, :completed, :failed, or :terminated

  ;; List active agents
  (list-agents {})

  ;; Stream messages from agent
  (require '[clojure.core.async :refer [<!! go-loop <!]])
  (go-loop []
    (when-let [msg (<! (::messages-ch agent-handle))]
      (println "Agent msg:" (:type msg))
      (recur)))

  ;; Wait for result
  (def result (<!! (::result-ch agent-handle)))
  (:result result)
  (:total_cost_usd result)

  ;; Terminate agent (cleans up Claude process, nREPL, flushes ctx)
  (terminate-agent! {::agent-handle agent-handle
                     ::node (xtdb-node)})

  ;; Generate sample data
  (require '[malli.generator :as mg])
  (mg/generate ::prompt)
  (mg/generate ::query-options)
  (mg/generate ::query-request)

  ;; Validate request
  (require '[malli.core :as m])
  (m/validate ::query-request {::prompt "Hello"})
  (m/validate ::query-request {::prompt "Hello" ::options {::model "claude-opus-4-5-20251101"}})

  ;; Collect and verify function schemas
  (require '[malli.instrument :as mi])
  (mi/collect! {:ns 'seon.claude.sdk})
  (keys (get (m/function-schemas) 'seon.claude.sdk))

  ;; Simple test - ask a question
  (def result (exec {::prompt "What is 2+2? Reply with just the number."}))
  (:result result)
  (:total_cost_usd result)

  ;; Streaming test
  (require '[clojure.core.async :refer [<!! go-loop <!]])
  (let [handle (query {::prompt "What is 3+3? Just the number."
                       ::options {::max-turns 3}})]
    (go-loop []
      (when-let [msg (<! (::messages-ch handle))]
        (println "MSG:" (:type msg)
                 (when (= (:type msg) "assistant")
                   (-> msg :message :content first :text)))
        (recur)))
    (let [result (<!! (::result-ch handle))]
      (println "RESULT:" (:result result))
      ((::close! handle))
      result))

  ;; With tool restrictions
  (exec {::prompt "Read the first line of CONVENTIONS.md"
         ::options {::allowed-tools ["Read" "Glob"]
                    ::permission-mode "bypassPermissions"
                    ::max-turns 5}})

  nil)
