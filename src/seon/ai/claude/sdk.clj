(ns seon.ai.claude.sdk
  "Claude Code CLI process management.

   Low-level functions for spawning and communicating with the Claude Code CLI.
   Used by seon.ai.claude provider for agent lifecycle management.

   ## Schemas

   - `::cli-command` - Path to Claude CLI executable
   - `::cwd` - Working directory for CLI process
   - `::permission-mode` - Permission mode for tool execution
   - `::max-turns` - Maximum conversation turns
   - `::max-budget-usd` - Cost limit in USD
   - `::allowed-tools` - Tool whitelist
   - `::disallowed-tools` - Tool denylist
   - `::mcp-servers` - MCP server configuration
   - `::settings-path` - Path to settings JSON file

   ## Functions

   - `(build-args opts)` - Build CLI argument vector
   - `(build-env)` - Build environment map
   - `(spawn-claude-code opts)` - Spawn CLI process
   - `(write-message! stdin msg)` - Write JSON message to stdin
   - `(parse-line line)` - Parse JSON line from stdout
   - `(make-user-message text)` - Create user message for CLI

   Example:

     (require '[seon.ai.claude.sdk :as sdk])

     ;; Spawn a Claude Code process
     (let [{:keys [process stdin stdout]} (sdk/spawn-claude-code {})]
       (sdk/write-message! stdin (sdk/make-user-message \"Hello\"))
       ;; Read responses from stdout...)"
  (:require
   [cheshire.core :as json]
   [clojure.java.process :as process]
   [clojure.string :as str]
   [seon.schema :as schema]
   [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

;; Permission modes for tool execution
(schema/register! ::permission-mode
                  [:enum "default" "acceptEdits" "bypassPermissions" "plan" "dontAsk"])

;; CLI configuration
(schema/register! ::cli-command
                  [:string {:min 1
                            :description "Path to Claude CLI executable"}])

(schema/register! ::cwd
                  [:string {:min 1
                            :description "Working directory for CLI process"}])

;; No arbitrary max limits - let Anthropic API limits be the constraint
(schema/register! ::max-turns
                  [:int {:min 1
                         :description "Maximum conversation turns (optional - no limit if not set)"}])

(schema/register! ::max-budget-usd
                  [:double {:min 0.0
                            :description "Cost limit in USD (optional - no limit if not set)"}])

(schema/register! ::allowed-tools
                  [:vector {:description "Tool whitelist"}
                   :string])

(schema/register! ::disallowed-tools
                  [:vector {:description "Tool denylist"}
                   :string])

(schema/register! ::mcp-servers
                  [:map-of {:description "MCP server configuration map"}
                   :keyword :map])

(schema/register! ::settings-path
                  [:string {:min 1
                            :description "Path to settings JSON file for hooks"}])

;; Chrome integration for browser automation
(schema/register! ::chrome
                  [:boolean {:description "Enable Claude in Chrome integration for browser automation"}])

;;; ---------------------------------------------------------------------------
;;; Configuration Constants
;;; ---------------------------------------------------------------------------

(def default-cli-command
  "Default Claude Code command. Reads from CLAUDE_CLI_PATH env var or uses user-local installation."
  (or (System/getenv "CLAUDE_CLI_PATH")
      "/Users/sean/.local/bin/claude"))

(def ^:const default-model
  "Default model. Opus 4.5 for complex tasks."
  "claude-opus-4-5-20251101")

(def ^:const default-permission-mode
  "Default permission mode."
  "default")

;; REMOVED: default-max-turns
;; We no longer set a default max-turns. The only limits should be:
;; 1. Anthropic API limits (tokens, rate limits)
;; 2. Explicit cost limits set by user (::max-budget-usd)
;; The Claude CLI's internal default of 100 turns is overridden by passing
;; a very high value only when launching agents (see seon.ai.claude/launch-agent!)

;;; ---------------------------------------------------------------------------
;;; CLI Argument and Environment Construction
;;; ---------------------------------------------------------------------------

(defn build-args
  "Build CLI arguments from options map.

   Options (all optional):
     ::cli-command     - Path to Claude CLI (default: ~/.local/bin/claude)
     ::model           - Model name (default: claude-opus-4-5-20251101)
     ::permission-mode - Permission mode (default: default)
     ::max-turns       - Maximum conversation turns
     ::max-budget-usd  - Cost limit in USD
     ::allowed-tools   - Vector of allowed tool names
     ::disallowed-tools - Vector of disallowed tool names
     ::mcp-servers     - MCP server configuration map
     ::settings-path   - Path to settings JSON file
     ::chrome          - Enable Chrome integration for browser automation

   Returns vector of CLI arguments."
  [{::keys [model permission-mode max-turns max-budget-usd
            allowed-tools disallowed-tools mcp-servers cli-command settings-path chrome]}]
  (let [cmd (or cli-command default-cli-command)
        model (or model default-model)
        perm (or permission-mode default-permission-mode)
        settings (or settings-path ".claude/settings.json")]
    (cond-> [cmd
             "--print"
             "--output-format" "stream-json"
             "--input-format" "stream-json"
             "--verbose"
             "--model" model
             "--permission-mode" perm
             "--setting-sources" "project,local"
             "--settings" settings]
      ;; Enable Chrome integration for browser automation
      chrome
      (conj "--chrome")

      ;; Only pass max-turns if explicitly set - no arbitrary defaults
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

(defn build-env
  "Build environment map with SDK identifier.

   Returns map of environment variables for the Claude CLI process.
   Sets CLAUDE_USE_SUBSCRIPTION=true and CLAUDE_CODE_ENTRYPOINT=sdk-clj."
  []
  (-> (into {} (System/getenv))
      (dissoc "CLAUDECODE")
      (assoc "ANTHROPIC_API_KEY" "")
      (assoc "CLAUDE_USE_SUBSCRIPTION" "true")
      (assoc "CLAUDE_CODE_ENTRYPOINT" "sdk-clj")))

;;; ---------------------------------------------------------------------------
;;; Message I/O
;;; ---------------------------------------------------------------------------

(defn make-user-message
  "Create a user message for the Claude Code CLI.

   Takes a text string and returns a message map in the format
   expected by the Claude Code CLI stdin stream."
  [text]
  {:type "user"
   :session_id ""
   :message {:role "user"
             :content [{:type "text" :text text}]}
   :parent_tool_use_id nil})

(defn write-message!
  "Write a JSON message to the process stdin.

   Args:
     stdin - OutputStream from the Claude process
     msg   - Message map to serialize and write

   Writes the message as JSON followed by newline, then flushes."
  [^java.io.OutputStream stdin msg]
  (let [json-str (str (json/generate-string msg) "\n")
        bytes (.getBytes json-str "UTF-8")]
    (.write stdin bytes)
    (.flush stdin)))

(defn parse-line
  "Parse a JSON line from stdout, returning error map on failure.

   Args:
     line - String line from stdout

   Returns parsed JSON as map with keyword keys, or error map:
     {:type \"parse_error\" :raw <line> :error <message>}"
  [line]
  (try
    (json/parse-string line true)
    (catch Exception e
      {:type "parse_error" :raw line :error (str e)})))

;;; ---------------------------------------------------------------------------
;;; Process Spawning
;;; ---------------------------------------------------------------------------

(defn spawn-claude-code
  "Spawn Claude Code CLI process.

   Options (all optional):
     ::cwd             - Working directory (default: \".\")
     ::cli-command     - Path to Claude CLI
     ::model           - Model name
     ::permission-mode - Permission mode
     ::max-turns       - Maximum turns
     ::max-budget-usd  - Cost limit
     ::allowed-tools   - Tool whitelist
     ::disallowed-tools - Tool denylist
     ::mcp-servers     - MCP server configuration
     ::settings-path   - Path to settings JSON

   Returns map with:
     :process  - Process handle
     :stdin    - OutputStream for writing
     :stdout   - InputStream for reading
     :stderr   - InputStream for errors
     :exit-ref - Delay containing exit code"
  [{::keys [cwd] :as opts}]
  (let [args (build-args opts)
        env (build-env)
        dir (or cwd ".")
        _ (log/debug "Spawning Claude Code" {:args args :cwd dir})
        proc (apply process/start {:dir dir :env env :clear-env true} args)]
    {:process proc
     :stdin (process/stdin proc)
     :stdout (process/stdout proc)
     :stderr (process/stderr proc)
     :exit-ref (process/exit-ref proc)}))

;;; ---------------------------------------------------------------------------
;;; Development Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  ;; View registered schemas
  (require '[seon.schema :as schema])
  (schema/schemas-in-namespace "seon.ai.claude.sdk")

  ;; Generate sample data
  (require '[malli.generator :as mg])
  (mg/generate ::permission-mode)
  (mg/generate ::max-turns)

  ;; Build args example
  (build-args {::model "claude-sonnet-4-20250514"
               ::max-turns 10
               ::allowed-tools ["Read" "Write"]})

  ;; Build env
  (build-env)

  ;; Make user message
  (make-user-message "Hello, Claude!")

  nil)
