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
     (claude/launch-agent! {::ai/namespace 'seon.trading
                            ::ai/prompt \"Implement feature X\"})

     ;; List running agents
     (claude/agents {})

     ;; Interrupt an agent
     (claude/interrupt! {::ai/session-id \"a1b2\"})"
  (:require
   [clojure.core.async :as async :refer [chan close!]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [integrant.repl.state :as state]
   [seon.ai :as ai]
   [seon.ai.agent :as agent]
   [seon.ai.agent.log :as agent-log]
   [seon.ai.claude.sdk :as sdk]
   [seon.orchestrator.session :as session]
   [seon.render.code :as render-code]
   [seon.runtime :as runtime]
   [seon.system :as sys]
   [seon.health :as health]
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
                   [:seon/id ::ai/message-id]
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

;; Files to include in agent context (vector of relative file paths)
(schema/register! ::files
                  [:vector {:description "Vector of relative file paths to include as context in the agent prompt"}
                   :string])

;; Launch request - uses base schemas where appropriate
;; SDK-related schemas (permission-mode, max-turns, etc.) are in seon.ai.claude.sdk
(schema/register! ::launch-agent-request
                  [:map
                   [::ai/namespace ::ai/namespace]
                   [::ai/prompt ::ai/prompt]
                   [::model {:optional true} ::model]
                   [::files {:optional true} ::files]
                   [::sdk/permission-mode {:optional true} ::sdk/permission-mode]
                   [::sdk/max-turns {:optional true} ::sdk/max-turns]
                   [::sdk/max-budget-usd {:optional true} ::sdk/max-budget-usd]
                   [::sdk/allowed-tools {:optional true} ::sdk/allowed-tools]
                   [::sdk/disallowed-tools {:optional true} ::sdk/disallowed-tools]
                   [::sdk/chrome {:optional true} ::sdk/chrome]])

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

;; Get result request (4-char hex agent session ID)
(schema/register! ::get-result-request
                  [:map
                   [::ai/session-id ::ai/session-id]])

;; Result text from completed agent
(schema/register! ::result-text
                  [:string {:description "Final result text from agent"}])

;; Duration in milliseconds
(schema/register! ::duration-ms
                  [:int {:min 0
                         :description "Duration in milliseconds"}])

;; Number of conversation turns
(schema/register! ::num-turns
                  [:int {:min 0
                         :description "Number of conversation turns"}])

;; Get result response
(schema/register! ::get-result-response
                  [:map
                   [::result-text {:optional true} ::result-text]
                   [::agent-status ::agent-status]
                   [::cost-usd {:optional true} [:double {:min 0.0}]]
                   [::duration-ms {:optional true} ::duration-ms]
                   [::num-turns {:optional true} ::num-turns]
                   [::error {:optional true} :string]])

;; Timeout for blocking operations (defaults to indefinite)
(schema/register! ::timeout-ms
                  [:int {:min 0
                         :description "Timeout in milliseconds for blocking operations"}])

;; Launch agent!! (blocking) request - extends launch-agent! request with timeout
(schema/register! ::launch-agent!!-request
                  [:map
                   [::ai/namespace ::ai/namespace]
                   [::ai/prompt ::ai/prompt]
                   [::model {:optional true} ::model]
                   [::files {:optional true} ::files]
                   [::skip-context? {:optional true} :boolean]
                   [::sdk/permission-mode {:optional true} ::sdk/permission-mode]
                   [::sdk/max-turns {:optional true} ::sdk/max-turns]
                   [::sdk/max-budget-usd {:optional true} ::sdk/max-budget-usd]
                   [::sdk/allowed-tools {:optional true} ::sdk/allowed-tools]
                   [::sdk/disallowed-tools {:optional true} ::sdk/disallowed-tools]
                   [::sdk/chrome {:optional true} ::sdk/chrome]
                   [::timeout-ms {:optional true} ::timeout-ms]])

;; Launch agent!! (blocking) response
(schema/register! ::launch-agent!!-response
                  [:map
                   [::result-text {:optional true} ::result-text]
                   [::agent-status ::agent-status]
                   [::cost-usd {:optional true} [:double {:min 0.0}]]
                   [::duration-ms {:optional true} ::duration-ms]
                   [::num-turns {:optional true} ::num-turns]
                   [::error {:optional true} :string]])

;; Agents (list) request/response
(schema/register! ::agents-request
                  [:map])

(schema/register! ::agents-response
                  [:vector ::agent-summary])

;; Note: sdk-message->entity and agent lifecycle functions do not have
;; :malli/schema metadata because they involve process spawning
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

(def ^:private max-tool-result-content-size
  "Maximum size for tool result content before truncation.
   Large file reads from Claude Code can be 50-100KB, which overloads storage.
   2KB preview is enough to understand what was read."
  2048)

(defn- truncate-tool-result-content
  "Truncate large tool result content to prevent storage overload.
   Returns content map with :content-preview, :content-size, :truncated? for large content."
  [content]
  (cond
    ;; String content - truncate if large
    (string? content)
    (if (> (count content) max-tool-result-content-size)
      {:content-preview (subs content 0 max-tool-result-content-size)
       :content-size (count content)
       :truncated? true}
      content)

    ;; Already a map - check for nested content
    (map? content)
    content

    ;; Other types - pass through
    :else content))

(defn- extract-tool-results
  "Extract tool_result blocks from user message content.
   Large content (>2KB) is truncated to prevent storage overload from file reads.
   Nil content is omitted (not stored as nil to avoid DB NPE)."
  [content]
  (when (sequential? content)
    (->> content
         (filter #(= "tool_result" (:type %)))
         (mapv (fn [tr]
                 (let [base (select-keys tr [:tool_use_id :is_error])
                       truncated (truncate-tool-result-content (:content tr))]
                   ;; Only add :content if present (nil breaks the DB)
                   (cond-> base
                     (some? truncated)
                     (assoc :content truncated)))))
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
   entity suitable for Datahike storage. Claude-specific attributes are preserved
   under the ::seon.ai.claude namespace.

   Request keys:
     ::sdk-message - Required. Raw SDK message map with keys like :type, :message, :uuid
     ::ai/session-id - Optional. Parent AI session ID to attach to entity

   Response keys:
     Returns a message entity map with :seon/id and namespaced attributes

   Example:
     (sdk-message->entity {::sdk-message {:type \"assistant\"
                                          :uuid \"msg-123\"
                                          :message {:role \"assistant\"
                                                    :content [{:type \"text\" :text \"Hello\"}]}}})
     ;; => {:seon/id \"msg-abc123\"
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
    (cond-> {:seon/id (str "msg-" (java.util.UUID/randomUUID))
             ::ai/type :message
             ::ai/role role
             ::ai/content text-content
             ::ai/timestamp (Instant/now)}
      ;; Message type - only include if present (nil breaks the DB)
      msg-type
      (assoc ::message-type msg-type)

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
      (assoc ::cache-read-tokens (:cache_read_input_tokens usage))

      ;; Result subtype (success, error_max_turns, etc.)
      (:subtype sdk-msg)
      (assoc ::result-subtype (:subtype sdk-msg)))))

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
;; Returns entity map suitable for Datahike storage.
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
  "Persist a Claude SDK message as an AI message entity.

   Converts the SDK message to an entity and stores it in Datahike.
   This is used internally by launch-agent! to auto-persist all messages.

   Request keys:
     ::ai/session-id - Required. AI session ID (from ai/start-session!)
     ::sdk-message   - Required. Raw SDK message from Claude Code CLI

   Response keys:
     ::ai/message-id - The generated message ID

   Example:
     (persist-message! {::ai/session-id \"ses-abc123\"
                        ::sdk-message {:type \"assistant\" ...}})"
  [{::ai/keys [session-id] ::keys [sdk-message]}]
  (let [entity (sdk-message->entity {::sdk-message sdk-message
                                     ::ai/session-id session-id})
        message-id (:seon/id entity)]
    ;; FIXME: port to :seon.ai datahike namespace via seon.db/transact!
    ;; Register `:seon.ai` in `:seon.db/flow` and rewire this to `seon.db/transact!`.
    {::ai/message-id message-id}))

;;; ---------------------------------------------------------------------------
;;; Private Agent Helpers
;;; ---------------------------------------------------------------------------

(defn- build-agent-mcp-config
  "Build MCP config that passes session_id to the agent."
  [session-id]
  {:seon {:command "./bin/mcp-server"
          :env {"SEON_SESSION_ID" session-id}}})

(def ^:private agent-instructions-path "AGENT.md")

(defn- load-agent-instructions
  "Load agent instructions from AGENT.md, returning empty string if not found."
  []
  (let [f (io/file agent-instructions-path)]
    (if (.exists f)
      (str (slurp f) "\n\n---\n\n")
      "")))

(defn- expand-home
  "Expand ~ to user's home directory in file paths."
  [path]
  (if (str/starts-with? path "~")
    (str/replace-first path "~" (System/getProperty "user.home"))
    path))

(defn- detect-language
  "Detect language from file extension for syntax highlighting."
  [path]
  (let [ext (some-> path (str/split #"\.") last)]
    (case ext
      ("clj" "cljs" "cljc" "edn") "clojure"
      ("md" "markdown") "markdown"
      ("json") "json"
      ("js" "mjs") "javascript"
      ("ts" "tsx") "typescript"
      ("html" "htm") "html"
      ("css") "css"
      ("sql") "sql"
      ("sh" "bash") "bash"
      ("yaml" "yml") "yaml"
      "")))

(defn- read-file-context
  "Read a file and return structured context map.
   Returns map with ::ai/path, ::ai/content, ::ai/language, ::ai/byte-count,
   ::ai/read-success, and optionally ::ai/error."
  [path]
  (try
    (let [expanded-path (expand-home path)
          f (io/file expanded-path)
          content (slurp f)
          lang (detect-language path)]
      {::ai/path path
       ::ai/content content
       ::ai/language lang
       ::ai/byte-count (count (.getBytes content "UTF-8"))
       ::ai/read-success true})
    (catch Exception e
      (log/warn "Failed to read file for agent context" {:path path :error (.getMessage e)})
      {::ai/path path
       ::ai/content ""
       ::ai/language ""
       ::ai/byte-count 0
       ::ai/read-success false
       ::ai/error (.getMessage e)})))

(defn- build-files-context
  "Build structured files context from a vector of file paths.
   Returns vector of file context maps suitable for ::ai/files-context."
  [files]
  (when (seq files)
    (mapv read-file-context files)))

(defn- format-file-context
  "Read files and format them as context for the agent prompt.
   Returns formatted string or nil if no files/errors.

   Files can be relative paths (from project root), absolute paths, or use ~ for home."
  [files]
  (when (seq files)
    (let [file-contents
          (for [path files]
            (try
              (let [expanded-path (expand-home path)
                    f (io/file expanded-path)
                    content (slurp f)
                    ;; Detect file type for syntax highlighting
                    ext (some-> path (str/split #"\.") last)
                    lang (case ext
                           ("clj" "cljs" "cljc" "edn") "clojure"
                           ("md" "markdown") "markdown"
                           ("json") "json"
                           ("js" "mjs") "javascript"
                           ("ts" "tsx") "typescript"
                           ("html" "htm") "html"
                           ("css") "css"
                           ("sql") "sql"
                           ("sh" "bash") "bash"
                           ("yaml" "yml") "yaml"
                           "")]
                (str "### " path "\n```" lang "\n" content "\n```\n"))
              (catch Exception e
                (log/warn "Failed to read file for agent context" {:path path :error (.getMessage e)})
                (str "### " path "\n[Error reading file: " (.getMessage e) "]\n"))))]
      (str "\n\n---\n\n# Reference Files\n\n"
           "The following files are provided as context for your task:\n\n"
           (str/join "\n" file-contents)))))

(defn- build-namespace-context
  "Build namespace documentation context from the knowledge graph.
   Returns a formatted string section, or nil if graph is unavailable."
  [namespace]
  (try
    (when-let [graph-db (some-> state/system :seon.graph/scanner :graph-db)]
      (when graph-db
        (let [ns-str (str namespace)
              result (render-code/context-for-agent
                      {::render-code/db-name :seon.runtime
                       ::render-code/ns-name ns-str})
              docs (:seon.render/documentation result)]
          (when-not (str/blank? docs)
            (str "\n\n---\n\n# Namespace Context\n\n"
                 "Documentation and call graph for `" ns-str "` from the knowledge graph:\n\n"
                 docs "\n")))))
    (catch Exception e
      (log/warn "Failed to build namespace context" {:namespace namespace :error (.getMessage e)})
      nil)))

(defn- build-health-context
  "Build a system health summary for agent prompts.
   Returns a formatted string section, or nil on failure."
  []
  (try
    (let [result (health/check {})]
      (str "\n\n# System Health\n\n"
           "```edn\n" (pr-str result) "\n```\n"))
    (catch Exception e
      (log/debug "Failed to build health context" {:error (.getMessage e)})
      nil)))

(defn- build-agent-prompt
  "Build the agent prompt with session context, AGENT.md instructions, and optional file context.
   When skip-context? is true, omits the namespace graph context (saves tokens)."
  ([session-id namespace prompt files]
   (build-agent-prompt session-id namespace prompt files false))
  ([session-id namespace prompt files skip-context?]
   (let [file-context (format-file-context files)
         health-context (build-health-context)
         ns-context (when-not skip-context?
                      (build-namespace-context namespace))]
     (str (load-agent-instructions)
          ;; Just the dynamic values - AGENT.md already explains how to use them
          "# Your Session\n\n"
          "- **Session ID**: `" session-id "`\n"
          "- **Namespace**: `" namespace "`\n"
          "- **MCP eval**: `eval(session_id=\"" session-id "\", code=\"...\")`\n\n"
          ;; Include system health early so agents know what's working
          (when health-context health-context)
          ;; Include namespace context from graph if available
          (when ns-context ns-context)
          ;; Include file context if provided
          (when file-context file-context)
          "---\n\n"
          "# Your Task\n\n" prompt))))

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
  "Check if message type should be persisted.
   We persist user, assistant, system, and result messages.
   We skip keep_alive and parse_error messages."
  [msg-type]
  (#{"user" "assistant" "system" "result"} msg-type))

(defn launch-agent!
  "Launch a Claude Code agent with an isolated Seon session.

   Creates everything the agent needs:
   - Persisted ctx atom for state management
   - Dedicated nREPL server
   - Claude Code process with MCP configured
   - AI session in Datahike for conversation persistence

   All SDK messages are automatically persisted to Datahike during execution.
   On completion, the AI session is closed with final stats (tokens, cost).

   Request keys:
     ::ai/namespace          - Required. Agent namespace (string or symbol)
     ::ai/prompt             - Required. Task description for the agent
     ::model                 - Optional. Claude model (default: opus)
     ::files                 - Optional. Vector of file paths to include as context.
                               Files are read and included in the agent's prompt.
                               Use this to share PRDs, plans, or relevant code.
     ::sdk/permission-mode   - Optional. Permission mode (default: bypassPermissions)
     ::sdk/max-turns         - Optional. Max conversation turns
     ::sdk/max-budget-usd    - Optional. Cost limit
     ::sdk/allowed-tools     - Optional. Tool whitelist
     ::sdk/disallowed-tools  - Optional. Tool denylist
     ::sdk/chrome            - Optional. Enable Chrome integration for browser automation
     ::ai/force?             - Optional. Force launch even if namespace has running agent

   Response keys (agent handle):
     ::ai/session-id      - 4-char hex session ID (Seon agent session)
     ::ai-session-id      - AI conversation session ID (for Datahike queries)
     ::ai/namespace       - Agent namespace
     ::nrepl-port         - nREPL port for the agent
     ::messages-ch        - Channel of SDK messages
     ::result-ch          - Channel receiving final result
     ::status-atom        - Atom with current status (:running, :completed, :failed)
     ::close!             - Function to terminate agent

   Example:
     (launch-agent! {::ai/namespace 'seon.trading
                     ::ai/prompt \"Implement the signals dashboard\"})

     ;; With file context
     (launch-agent! {::ai/namespace 'seon.trading
                     ::ai/prompt \"Read the PRD and implement Phase 1.\"
                     ::files [\"docs/prds/my-feature/prd.md\"
                              \"docs/prds/my-feature/plan.md\"]})"
  [{::ai/keys [namespace prompt force?]
    ::keys [model files skip-context?]
    ::sdk/keys [permission-mode max-turns max-budget-usd allowed-tools disallowed-tools chrome]}]
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
        (session/start-agent-session! {::session/namespace namespace})]

    (when (= :error (::session/status session-result))
      (throw (ex-info "Failed to create agent session"
                      {:namespace namespace
                       :error (::session/error session-result)})))

    (log/info "Created agent session" {:session-id id :port nrepl-port})

    ;; 2. Build initial context for persistence
    ;;    This captures the structured data (files, instructions) separately from the prompt
    (let [files-context (build-files-context files)
          agent-instructions (load-agent-instructions)
          initial-context (cond-> {::ai/task-prompt prompt}
                            (seq files-context) (assoc ::ai/files-context files-context)
                            (not (str/blank? agent-instructions))
                            (assoc ::ai/agent-instructions agent-instructions
                                   ::ai/agent-instructions-path agent-instructions-path))

          ;; 2b. Create AI session for conversation persistence
          ;;     Store the Seon session ID so completed sessions can find their log files
          {ai-session-id ::ai/session-id}
          (ai/start-session! {::ai/namespace namespace
                              ::ai/prompt prompt
                              ::ai/agent-session-id id
                              ::ai/initial-context initial-context})
          _ (log/info "Created AI session for persistence" {:ai-session-id ai-session-id})

          ;; 2c. Create structured agent log for real-time tailing
          agent-logger (agent-log/create-logger! {::agent-log/session-id id})
          _ (agent-log/log-launch! agent-logger {::agent-log/namespace (str namespace)
                                                 ::agent-log/port nrepl-port})

          ;; 3. Build agent prompt with session context and optional file context
          full-prompt (build-agent-prompt id namespace prompt files skip-context?)

          ;; 4. Build MCP config with session_id in environment
          mcp-config (build-agent-mcp-config id)

          ;; 5. Spawn Claude Code with configured MCP
          ;; NOTE: Despite docs claiming "no default limit", Claude CLI v2.1.x appears to
          ;; have an undocumented 100 turn limit. We override with 10000 to effectively
          ;; disable it. The real constraints should be API token limits and cost budgets.
          ;; TODO: File bug report / verify with Anthropic about actual default behavior.
          effective-max-turns (or max-turns 10000)
          status-atom (atom :running)
          {:keys [process stdin stdout]}
          (sdk/spawn-claude-code (cond-> {::sdk/model (or model sdk/default-model)
                                          ::sdk/permission-mode (or permission-mode "bypassPermissions")
                                          ::sdk/mcp-servers mcp-config
                                          ::sdk/max-turns effective-max-turns
                                          ;; Enable Chrome by default for browser automation
                                          ::sdk/chrome (if (some? chrome) chrome true)}
                                   max-budget-usd (assoc ::sdk/max-budget-usd max-budget-usd)
                                   allowed-tools (assoc ::sdk/allowed-tools allowed-tools)
                                   disallowed-tools (assoc ::sdk/disallowed-tools disallowed-tools)))

          ;; Use sliding buffer to prevent blocking when channel fills up.
          ;; Without this, >!! blocks at 100 messages and deadlocks the reader.
          ;; Old messages drop if not consumed, but agent keeps running.
          messages-ch (chan (async/sliding-buffer 1000))
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

          ;; 6. Track last activity time for stuck detection
          last-activity-at (atom (Instant/now))

          ;; 6b. Atoms to capture agent run stats from result message
          ;; These are set when the result message arrives, then read in the finally block
          ;; to pass to runtime/complete-agent-run!
          run-cost-usd (atom nil)
          run-num-turns (atom nil)
          run-duration-ms (atom nil)

          ;; 7. Start reader that persists messages and updates status
          claude-session-mapped? (atom false)
          _reader (future
                    (try
                      (with-open [rdr (io/reader stdout)]
                        (loop []
                          (when-let [line (.readLine rdr)]
                            (when-not (str/blank? line)
                              ;; Update last activity time for stuck detection
                              (reset! last-activity-at (Instant/now))
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

                                ;; Persist message to Datahike (skip keep_alive, parse_error)
                                ;; Retry once after 100ms on failure
                                (when (persistable-message-type? msg-type)
                                  (try
                                    (persist-message! {::ai/session-id ai-session-id
                                                       ::sdk-message msg})
                                    (catch Exception e
                                      (log/debug "First persist attempt failed, retrying..."
                                                 {:session-id id :msg-type msg-type})
                                      (Thread/sleep 100)
                                      (try
                                        (persist-message! {::ai/session-id ai-session-id
                                                           ::sdk-message msg})
                                        (catch Exception e2
                                          (log/warn e2 "Failed to persist message after retry"
                                                    {:session-id id :msg-type msg-type}))))))

                                ;; Log to agent log file for real-time tailing
                                (try
                                  (agent-log/log-sdk-message! agent-logger msg)
                                  (catch Exception e
                                    (log/warn e "Failed to log SDK message"
                                              {:session-id id :msg-type msg-type})))

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
                                      (ai/end-session! {::ai/session-id ai-session-id
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
                                    ;; Capture run stats for complete-agent-run!
                                    (reset! run-cost-usd (:total_cost_usd msg))
                                    (reset! run-num-turns (:num_turns msg))
                                    (reset! run-duration-ms (:duration_ms msg))
                                    ;; Update agent status
                                    (reset! status-atom final-status)))))
                            ;; Only recur if still running - exit loop on result/failure
                            ;; This allows the finally block to run and clean up resources
                            (when (= :running @status-atom)
                              (recur)))))
                      (catch Exception e
                        (log/warn e "Agent reader error" {:session-id id})
                        (reset! status-atom :failed)
                        ;; End AI session as failed on reader error
                        (try
                          (ai/end-session! {::ai/session-id ai-session-id
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
                            (ai/end-session! {::ai/session-id ai-session-id
                                              ::ai/status :terminated})
                            (catch Exception _)))
                        ;; Destroy Claude process to prevent orphans
                        (try
                          (.destroy process)
                          (catch Exception e
                            (log/debug "Error destroying Claude process" {:error (str e)})))
                        ;; Clean up channels
                        (close! messages-ch)
                        (close! result-ch)
                        ;; Close agent logger
                        (agent-log/close-logger! agent-logger)
                        ;; Stop Seon session (flushes ctx, stops nREPL)
                        ;; This is critical to avoid orphaned nREPL servers
                        (try
                          (session/stop-agent-session! {::session/id id})
                          (catch Exception e
                            (log/warn e "Failed to stop agent session on reader exit"
                                      {:session-id id})))
                        ;; Complete agent run in runtime registry
                        (try
                          (runtime/complete-agent-run!
                           (cond-> {::runtime/agent-run-id id
                                    ::runtime/status (or @status-atom :failed)}
                             @run-cost-usd (assoc ::runtime/cost-usd @run-cost-usd)
                             @run-num-turns (assoc ::runtime/num-turns @run-num-turns)
                             @run-duration-ms (assoc ::runtime/duration-ms @run-duration-ms)))
                          (catch Exception e
                            (log/warn "Failed to complete agent run in runtime"
                                      {:error (.getMessage e)})))
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
                         (ai/end-session! {::ai/session-id ai-session-id
                                           ::ai/status :interrupted})
                         (catch Exception e
                           (log/warn e "Failed to end AI session on close"))))
                     ;; Stop Seon session (flushes ctx, stops nREPL)
                     (session/stop-agent-session! {::session/id id})
                     ;; Complete agent run in runtime registry
                     (try
                       (runtime/complete-agent-run!
                        {::runtime/agent-run-id id
                         ::runtime/status :interrupted})
                       (catch Exception e
                         (log/warn "Failed to complete agent run on close"
                                   {:error (.getMessage e)})))
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
                  ::agent/last-activity-at last-activity-at
                  ::agent/process process
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

      ;; 10. Record agent run in runtime registry
      (try
        (runtime/start-agent-run! {::runtime/agent-run-id id
                                   ::runtime/namespace (str namespace)
                                   ::runtime/provider :claude})
        (catch Exception e
          (log/warn "Failed to start agent run in runtime" {:error (.getMessage e)})))

      (log/info "Agent launched" {:session-id id
                                  :ai-session-id ai-session-id
                                  :namespace namespace
                                  :port nrepl-port})

      handle)))

(defn launch-agent!!
  "Launch a Claude Code agent and block until completion.

   This is a blocking variant of `launch-agent!` that waits for the agent
   to complete and returns the result. Useful for orchestrators that need
   to wait for agent completion before proceeding.

   Request keys:
     ::ai/namespace          - Required. Agent namespace (string or symbol)
     ::ai/prompt             - Required. Task description for the agent
     ::model                 - Optional. Claude model (default: opus)
     ::files                 - Optional. Vector of file paths to include as context.
                               Files are read and included in the agent's prompt.
                               Use this to share PRDs, plans, or relevant code.
     ::sdk/permission-mode   - Optional. Permission mode (default: bypassPermissions)
     ::sdk/max-turns         - Optional. Max conversation turns
     ::sdk/max-budget-usd    - Optional. Cost limit
     ::sdk/allowed-tools     - Optional. Tool whitelist
     ::sdk/disallowed-tools  - Optional. Tool denylist
     ::ai/force?             - Optional. Force launch even if namespace has running agent
     ::timeout-ms            - Optional. Timeout in milliseconds (default: wait indefinitely)

   Response keys:
     ::result-text   - The final result text (if completed successfully)
     ::agent-status  - Final status (:completed, :failed, :timeout, :interrupted)
     ::cost-usd      - Total cost in USD (if available)
     ::duration-ms   - Duration in milliseconds
     ::num-turns     - Number of conversation turns
     ::error         - Error message (if failed or timeout)

   Example:
     (launch-agent!! {::ai/namespace 'seon.trading
                      ::ai/prompt \"Implement the signals dashboard\"
                      ::timeout-ms 300000})  ; 5 minute timeout

     ;; With file context
     (launch-agent!! {::ai/namespace 'seon.feature
                      ::ai/prompt \"Implement the feature.\"
                      ::files [\"docs/prds/feature/prd.md\"]})
     ;; => {::result-text \"## Summary\\n\\n...\"
     ;;     ::agent-status :completed
     ;;     ::cost-usd 0.23
     ;;     ::duration-ms 15185
     ;;     ::num-turns 3}"
  [{::ai/keys [namespace prompt force?]
    ::keys [model files timeout-ms]
    ::sdk/keys [permission-mode max-turns max-budget-usd allowed-tools disallowed-tools]
    :as request}]
  (log/info "Launching blocking agent" {:namespace namespace :timeout-ms timeout-ms})

  ;; Launch the agent (non-blocking)
  (let [handle (launch-agent! (dissoc request ::timeout-ms))
        session-id (::ai/session-id handle)
        result-ch (::result-ch handle)
        close-fn (::close! handle)
        start-time (System/currentTimeMillis)]

    ;; Block on result-ch with optional timeout
    (let [result-msg (if timeout-ms
                       ;; With timeout
                       (async/alt!!
                         result-ch ([v] v)
                         (async/timeout timeout-ms) ::timeout)
                       ;; No timeout - wait indefinitely
                       (async/<!! result-ch))]

      (cond
        ;; Timeout occurred
        (= result-msg ::timeout)
        (do
          (log/warn "Agent timed out" {:session-id session-id :timeout-ms timeout-ms})
          ;; Clean up the agent
          (when close-fn (close-fn))
          {::agent-status :timeout
           ::duration-ms (- (System/currentTimeMillis) start-time)
           ::error (str "Agent timed out after " timeout-ms "ms")})

        ;; Got a result message - parse it
        result-msg
        (let [parsed (agent/parse-result {:provider :claude :message result-msg})]
          (log/info "Agent completed" {:session-id session-id
                                       :status (::agent/status parsed)
                                       :cost (::agent/cost-usd parsed)})
          (cond-> {::agent-status (::agent/status parsed)
                   ::duration-ms (or (::agent/duration-ms parsed)
                                     (- (System/currentTimeMillis) start-time))}
            (::agent/result-text parsed)
            (assoc ::result-text (::agent/result-text parsed))

            (::agent/cost-usd parsed)
            (assoc ::cost-usd (::agent/cost-usd parsed))

            (::agent/num-turns parsed)
            (assoc ::num-turns (::agent/num-turns parsed))))

        ;; Channel closed without result (unexpected termination)
        :else
        (do
          (log/warn "Agent terminated unexpectedly" {:session-id session-id})
          {::agent-status :terminated
           ::duration-ms (- (System/currentTimeMillis) start-time)
           ::error "Agent terminated without returning a result"})))))

(defn agents
  "List all running Claude agents with status, namespace, session-id.

   Delegates to seon.ai.agent/agents and filters for Claude agents,
   mapping response keys to Claude-specific namespace for backwards compatibility.

   Request keys:
     (none - empty map for consistency)

   Response keys (vector of):
     ::ai/session-id    - 4-char hex session ID
     ::ai/namespace     - Agent namespace
     ::nrepl-port       - nREPL port for direct REPL access
     ::agent-status     - Current status (:running, :completed, :failed, :terminated)
     ::last-activity-at - Instant of last message received
     ::process-alive?   - Whether the Java Process is still alive

   Example:
     (agents {})
     ;; => [{:seon.ai/session-id \"a1b2\"
     ;;      :seon.ai/namespace \"seon.trading\"
     ;;      :seon.ai.claude/nrepl-port 7889
     ;;      :seon.ai.claude/agent-status :running
     ;;      :seon.ai.claude/process-alive? true
     ;;      :seon.ai.claude/last-activity-at #inst \"...\"}]

   Note: For cross-provider agent listing, use seon.ai.agent/agents instead."
  [_request]
  (->> (agent/agents {})
       (filter #(= :claude (::agent/provider %)))
       (mapv (fn [a]
               (cond-> {::ai/session-id (::agent/session-id a)
                        ::ai/namespace (::agent/namespace a)
                        ::nrepl-port (::agent/nrepl-port a)
                        ::agent-status (::agent/agent-status a)}
                 (::agent/last-activity-at a)
                 (assoc ::last-activity-at (::agent/last-activity-at a))
                 (some? (::agent/process-alive? a))
                 (assoc ::process-alive? (::agent/process-alive? a)))))))

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

(defn get-result
  "Get the final result from a completed agent session.

   Retrieves the result text and stats from a completed agent. The session-id
   is the 4-char hex Seon agent session ID (like \"d465\"), not the AI session ID.

   Request keys:
     ::ai/session-id - Required. The 4-char hex agent session ID

   Response keys:
     ::result-text    - The final result text (if completed successfully)
     ::agent-status   - Current status (:running, :completed, :failed, :terminated, :interrupted)
     ::result-subtype - Result subtype (\"success\", \"error_max_turns\", etc.) if available
     ::cost-usd       - Total cost in USD (if available)
     ::duration-ms    - Duration in milliseconds (if completed)
     ::num-turns      - Number of conversation turns (counted from assistant messages)
     ::error          - Error message if agent not found

   Example:
     (get-result {::ai/session-id \"d465\"})
     ;; => {::result-text \"## Summary\\n\\n**REPL connection verified...**\"
     ;;     ::agent-status :completed
     ;;     ::result-subtype \"success\"
     ;;     ::cost-usd 0.23
     ;;     ::duration-ms 15185
     ;;     ::num-turns 3}

   Note: This function queries the database, so it works for both running
   and completed agents. For running agents, ::result-text will be nil."
  [{::ai/keys [session-id]}]
  ;; FIXME: port to :seon.ai datahike namespace via seon.db/transact!
  ;; Until `:seon.ai` is wired into `:seon.db/flow`, agent-result returns a
  ;; stub indicating the persistence layer is down.
  {::agent-status :failed
   ::error (str "Agent session lookup unavailable: " session-id)})
(defn agent-messages
  "Get recent messages from an agent session.

   Queries Datahike for messages, returning them in chronological order.
   Useful for checking agent progress without blocking.

   Request keys:
     ::ai/session-id - Required. The 4-char hex agent session ID
     ::limit         - Optional. Max messages to return (default: 20)

   Response keys:
     ::messages      - Vector of message maps with role, content, timestamp
     ::agent-status  - Current status (:running, :completed, :failed, etc.)
     ::message-count - Total messages in session
     ::error         - Error message if agent not found

   Example:
     (agent-messages {::ai/session-id \"a1b2\"})
     ;; => {::messages [{:role \"assistant\" :content \"I'll start by...\"}
     ;;                 {:role \"assistant\" :content \"[tool: Read]\"}]
     ;;     ::agent-status :running
     ;;     ::message-count 5}"
  [{::ai/keys [session-id] ::keys [_limit] :or {_limit 20}}]
  ;; FIXME(M-3): port to :seon.ai datahike namespace via seon.db/query.
  {::messages []
   ::agent-status :not-found
   ::message-count 0
   ::error (str "Agent message history unavailable (M-3 pending): " session-id)})
(defn wait-for-agent!!
  "Block until a running agent completes and return its result.

   Use this to re-attach to an agent after an MCP eval timeout, or to wait
   for an agent launched with `launch-agent!`.

   Request keys:
     ::ai/session-id - Required. The 4-char hex agent session ID
     ::timeout-ms    - Optional. Timeout in milliseconds (default: wait indefinitely)

   Response keys:
     ::result-text   - The final result text (if completed successfully)
     ::agent-status  - Final status (:completed, :failed, :timeout, :not-found)
     ::cost-usd      - Total cost in USD (if available)
     ::duration-ms   - Duration in milliseconds
     ::num-turns     - Number of conversation turns
     ::error         - Error message (if failed, timeout, or not found)

   Example:
     ;; After MCP eval timeout, re-attach to running agent
     (wait-for-agent!! {::ai/session-id \"a1b2\"})
     ;; => {::result-text \"## Summary\\n\\n...\" ::agent-status :completed}

   Note: If the agent already completed, returns the result from the database."
  [{::ai/keys [session-id] ::keys [timeout-ms]}]
  (log/info "Waiting for agent" {:session-id session-id :timeout-ms timeout-ms})

  ;; First check if agent is in registry (still running)
  (if-let [handle (agent/get-agent {::agent/session-id session-id})]
    (let [result-ch (::result-ch handle)
          status-atom (::agent/status-atom handle)
          start-time (System/currentTimeMillis)]

      ;; Check if already completed
      (if (not= :running @status-atom)
        ;; Already done - get result from database
        (do
          (log/info "Agent already completed" {:session-id session-id :status @status-atom})
          (get-result {::ai/session-id session-id}))

        ;; Still running - block on result channel
        (let [result-msg (if timeout-ms
                           (async/alt!!
                             result-ch ([v] v)
                             (async/timeout timeout-ms) ::timeout)
                           (async/<!! result-ch))]
          (cond
            (= result-msg ::timeout)
            (do
              (log/warn "Wait timed out" {:session-id session-id :timeout-ms timeout-ms})
              {::agent-status :timeout
               ::duration-ms (- (System/currentTimeMillis) start-time)
               ::error (str "Timed out waiting for agent after " timeout-ms "ms")})

            result-msg
            (let [parsed (agent/parse-result {:provider :claude :message result-msg})]
              (log/info "Agent completed" {:session-id session-id :status (::agent/status parsed)})
              (cond-> {::agent-status (::agent/status parsed)
                       ::duration-ms (or (::agent/duration-ms parsed)
                                         (- (System/currentTimeMillis) start-time))}
                (::agent/result-text parsed)
                (assoc ::result-text (::agent/result-text parsed))
                (::agent/cost-usd parsed)
                (assoc ::cost-usd (::agent/cost-usd parsed))
                (::agent/num-turns parsed)
                (assoc ::num-turns (::agent/num-turns parsed))))

            :else
            {::agent-status :terminated
             ::duration-ms (- (System/currentTimeMillis) start-time)
             ::error "Agent terminated unexpectedly"}))))

    ;; Not in registry - check database for completed agent
    (let [result (get-result {::ai/session-id session-id})]
      (if (= :running (::agent-status result))
        ;; Weird state: DB says running but not in registry
        {::agent-status :not-found
         ::error (str "Agent " session-id " not found in registry. It may have crashed.")}
        ;; Already completed
        result))))

(defn wait-for-agents!!
  "Block until all agents complete and return their results.

   Waits for multiple agents in parallel using core.async.
   Returns a map of session-id -> result.

   Request keys:
     ::ai/session-ids - Required. Vector of 4-char hex agent session IDs
     ::timeout-ms     - Optional. Timeout in milliseconds (default: wait indefinitely)

   Example:
     (wait-for-agents!! {::ai/session-ids [\"a1b2\" \"c3d4\" \"e5f6\"]})
     ;; => {\"a1b2\" {::result-text \"...\" ::agent-status :completed}
     ;;     \"c3d4\" {::result-text \"...\" ::agent-status :completed}
     ;;     \"e5f6\" {::result-text \"...\" ::agent-status :completed}}"
  [{::ai/keys [session-ids] ::keys [timeout-ms]}]
  (log/info "Waiting for agents" {:session-ids session-ids :timeout-ms timeout-ms})

  ;; Launch async waits for each agent
  (let [result-chs (into {}
                         (map (fn [sid]
                                [sid (async/thread
                                       (wait-for-agent!! {::ai/session-id sid
                                                          ::timeout-ms timeout-ms}))]))
                         session-ids)
        results (atom {})]

    ;; Collect all results
    (doseq [[sid ch] result-chs]
      (let [result (async/<!! ch)]
        (swap! results assoc sid result)))

    (log/info "All agents completed" {:count (count session-ids)})
    @results))

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
