(ns seon.claude.sdk
  "Native Clojure SDK for spawning Claude Code CLI agents.

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
            allowed-tools disallowed-tools mcp-servers cli-command]}]
  (let [cmd (or cli-command default-cli-command)
        model (or model default-model)
        perm (or permission-mode default-permission-mode)]
    ;; Note: "claude" is a standalone executable (npm installed), not a node script
    (cond-> [cmd
             "--output-format" "stream-json"
             "--input-format" "stream-json"
             "--verbose"  ; REQUIRED for stream-json to work
             "--model" model
             "--permission-mode" perm]
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
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  ;; View registered schemas
  (require '[seon.schema :as schema])
  (schema/schemas-in-namespace "seon.claude.sdk")

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
