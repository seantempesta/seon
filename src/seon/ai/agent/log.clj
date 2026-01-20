(ns seon.ai.agent.log
  "Structured per-agent logging to logs/agents/{session-id}.log

   This namespace provides logging utilities for agent observability.
   Each agent gets its own log file that can be tailed in real-time:

     tail -f logs/agents/f602.log

   ## Log Format

   One line per event, structured for both humans and parsing:

     2026-01-20T13:23:20Z | LAUNCH  | seon.trading | port=7892
     2026-01-20T13:23:21Z | MESSAGE | assistant | \"I'll start by...\"
     2026-01-20T13:23:25Z | TOOL    | eval | (xt/q node \"SELECT...\")
     2026-01-20T13:23:26Z | RESULT  | eval | [{:column-name \"_id\"}...]
     2026-01-20T13:24:00Z | HOOK    | Write | tests=pass | gemini=pending
     2026-01-20T13:25:00Z | COMPLETE| cost=$0.45 | messages=84 | duration=100s

   ## Event Types

   - LAUNCH   - Agent started
   - MESSAGE  - Assistant or user message (content truncated)
   - TOOL     - Tool call started (input truncated)
   - RESULT   - Tool result received (content truncated)
   - HOOK     - Dev hook feedback
   - COMPLETE - Agent finished with stats

   ## Usage

     (require '[seon.ai.agent.log :as agent-log])

     ;; Initialize logger for an agent
     (def logger (agent-log/create-logger! {::session-id \"f602\"}))

     ;; Log events
     (agent-log/log-launch! logger {::namespace \"seon.trading\" ::port 7892})
     (agent-log/log-message! logger {::role \"assistant\" ::content \"I'll analyze...\"})
     (agent-log/log-tool! logger {::tool-name \"eval\" ::input \"(xt/q ...)\"})
     (agent-log/log-result! logger {::tool-name \"eval\" ::output \"[{:a 1}...]\"})
     (agent-log/log-complete! logger {::cost 0.45 ::messages 84 ::duration-ms 100000})

   ## File Management

   Log files are written to `logs/agents/{session-id}.log`.
   The directory is created automatically if it doesn't exist.
   Files are opened in append mode with immediate flush for real-time tailing.

   ## Schema Note

   Functions in this namespace do not have :malli/schema metadata because they
   involve runtime objects (BufferedWriter) that cannot be property tested.
   This follows the convention in CONVENTIONS.md for functions with opaque Java
   objects. Input/output types are documented in docstrings."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedWriter FileWriter]
           [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]))

;;; ---------------------------------------------------------------------------
;;; Constants
;;; ---------------------------------------------------------------------------

(def ^:private log-dir "logs/agents")

(def ^:private max-content-length
  "Maximum length of content (message text, tool input/output) before truncation."
  200)

(def ^:private max-tool-input-length
  "Maximum length of tool input before truncation."
  100)

(def ^:private max-tool-output-length
  "Maximum length of tool output before truncation."
  150)

(def ^:private iso-formatter
  "ISO 8601 timestamp formatter for log lines."
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'"))

;;; ---------------------------------------------------------------------------
;;; Private Helpers
;;; ---------------------------------------------------------------------------

(defn- ensure-log-dir!
  "Ensure the log directory exists."
  []
  (let [dir (io/file log-dir)]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn- format-timestamp
  "Format current time as ISO 8601 timestamp."
  []
  (.format iso-formatter (.atOffset (Instant/now) ZoneOffset/UTC)))

(defn- truncate
  "Truncate string to max-len, adding ... if truncated."
  [s max-len]
  (if (and s (> (count s) max-len))
    (str (subs s 0 (- max-len 3)) "...")
    s))

(defn- escape-newlines
  "Replace newlines with spaces for single-line log format."
  [s]
  (when s
    (-> s
        (str/replace #"\r?\n" " ")
        (str/replace #"\s+" " ")
        str/trim)))

(defn- format-content
  "Format content for log: escape newlines, truncate, and quote."
  [content max-len]
  (when content
    (let [clean (escape-newlines (str content))
          truncated (truncate clean max-len)]
      (str "\"" truncated "\""))))

(defn- format-log-line
  "Format a structured log line.

   Format: timestamp | EVENT | field1 | field2 | ...

   Event names are left-padded to 8 chars for alignment."
  [event-type & fields]
  (let [timestamp (format-timestamp)
        padded-event (format "%-8s" event-type)]
    (str timestamp " | " padded-event " | " (str/join " | " fields))))

(defn- write-line!
  "Write a line to the log file with immediate flush."
  [^BufferedWriter writer line]
  (.write writer ^String line)
  (.write writer "\n")
  (.flush writer))

;;; ---------------------------------------------------------------------------
;;; Logger Creation
;;; ---------------------------------------------------------------------------

(defn create-logger!
  "Create a logger for an agent session.

   Opens a log file for the session in append mode.
   Returns a map with:
     ::session-id - The session ID
     ::writer - The BufferedWriter for the log file
     ::path - Path to the log file

   Example:
     (def logger (create-logger! {::session-id \"f602\"}))"
  [{::keys [session-id]}]
  (ensure-log-dir!)
  (let [path (str log-dir "/" session-id ".log")
        writer (BufferedWriter. (FileWriter. (io/file path) true))] ; true = append
    {::session-id session-id
     ::writer writer
     ::path path}))

(defn close-logger!
  "Close a logger's file writer."
  [{::keys [writer]}]
  (when writer
    (.close ^BufferedWriter writer)))

;;; ---------------------------------------------------------------------------
;;; Logging Functions
;;; ---------------------------------------------------------------------------

(defn log-launch!
  "Log agent launch event.

   Fields: namespace | port=PORT"
  [{::keys [writer]} {::keys [namespace port]}]
  (when writer
    (let [line (format-log-line "LAUNCH" (str namespace) (str "port=" port))]
      (write-line! writer line))))

(defn log-message!
  "Log a message event (assistant or user).

   Fields: role | \"truncated content\""
  [{::keys [writer]} {::keys [role content]}]
  (when writer
    (let [formatted-content (format-content content max-content-length)
          line (format-log-line "MESSAGE" role formatted-content)]
      (write-line! writer line))))

(defn log-tool!
  "Log a tool call event.

   Fields: tool-name | \"truncated input\""
  [{::keys [writer]} {::keys [tool-name input]}]
  (when writer
    (let [formatted-input (format-content input max-tool-input-length)
          line (format-log-line "TOOL" tool-name formatted-input)]
      (write-line! writer line))))

(defn log-result!
  "Log a tool result event.

   Fields: tool-name | \"truncated output\""
  [{::keys [writer]} {::keys [tool-name output]}]
  (when writer
    (let [formatted-output (format-content output max-tool-output-length)
          line (format-log-line "RESULT" tool-name formatted-output)]
      (write-line! writer line))))

(defn log-hook!
  "Log a dev hook feedback event.

   Fields: file-type | tests=status | gemini=status"
  [{::keys [writer]} {::keys [file-type tests-status gemini-status]}]
  (when writer
    (let [line (format-log-line "HOOK"
                                (or file-type "unknown")
                                (str "tests=" (or tests-status "pending"))
                                (str "gemini=" (or gemini-status "pending")))]
      (write-line! writer line))))

(defn log-complete!
  "Log agent completion event.

   Fields: cost=$X.XX | messages=N | duration=Ns"
  [{::keys [writer]} {::keys [cost messages duration-ms subtype]}]
  (when writer
    (let [duration-s (when duration-ms (/ duration-ms 1000))
          fields (cond-> []
                   subtype (conj (str "subtype=" subtype))
                   cost (conj (str "cost=$" (format "%.2f" (double cost))))
                   messages (conj (str "messages=" messages))
                   duration-s (conj (str "duration=" (int duration-s) "s")))
          line (format-log-line "COMPLETE" (str/join " | " fields))]
      (write-line! writer line))))

(defn log-error!
  "Log an error event.

   Fields: \"error message\""
  [{::keys [writer]} {::keys [error]}]
  (when writer
    (let [line (format-log-line "ERROR" (format-content error max-content-length))]
      (write-line! writer line))))

;;; ---------------------------------------------------------------------------
;;; SDK Message Processing
;;; ---------------------------------------------------------------------------

(defn- extract-tool-calls
  "Extract tool call info from assistant message content blocks."
  [content]
  (when (sequential? content)
    (->> content
         (filter #(= "tool_use" (:type %)))
         (map (fn [block]
                {::tool-name (:name block)
                 ::input (pr-str (:input block))}))
         seq)))

(defn- extract-tool-results
  "Extract tool result info from user message content blocks."
  [content]
  (when (sequential? content)
    (->> content
         (filter #(= "tool_result" (:type %)))
         (map (fn [block]
                {::tool-name (:tool_use_id block)
                 ::output (pr-str (:content block))}))
         seq)))

(defn- extract-text-content
  "Extract text content from message, handling both string and structured content."
  [content]
  (cond
    (string? content) content
    (sequential? content)
    (->> content
         (filter #(= "text" (:type %)))
         (map :text)
         (str/join " "))
    :else (str content)))

(defn log-sdk-message!
  "Log a Claude SDK message, extracting appropriate info based on type.

   Handles:
   - assistant messages with text and/or tool_use blocks
   - user messages with tool_result blocks
   - result messages with completion stats
   - system messages"
  [logger sdk-message]
  (let [msg-type (:type sdk-message)
        inner-msg (:message sdk-message)
        role (or (:role inner-msg) msg-type)
        content (or (:content inner-msg) (:result sdk-message))]
    (case msg-type
      ;; Assistant messages - log text and any tool calls
      "assistant"
      (do
        ;; Log the text content if any
        (when-let [text (not-empty (extract-text-content content))]
          (log-message! logger {::role "assistant" ::content text}))
        ;; Log each tool call
        (doseq [tool-call (extract-tool-calls content)]
          (log-tool! logger tool-call)))

      ;; User messages - usually tool results from the SDK
      "user"
      (doseq [tool-result (extract-tool-results content)]
        (log-result! logger tool-result))

      ;; System messages
      "system"
      (log-message! logger {::role "system" ::content (extract-text-content content)})

      ;; Result message - completion with stats
      "result"
      (log-complete! logger {::subtype (:subtype sdk-message)
                             ::cost (:total_cost_usd sdk-message)
                             ::messages (:num_turns sdk-message)
                             ::duration-ms (:duration_ms sdk-message)})

      ;; Skip keep_alive and parse_error
      nil)))

(comment
  ;; REPL exploration

  ;; Create a test logger
  (def test-logger (create-logger! {::session-id "test-001"}))

  ;; Log some events
  (log-launch! test-logger {::namespace "seon.trading" ::port 7892})
  (log-message! test-logger {::role "assistant"
                             ::content "I'll start by analyzing the database schema to understand what tables are available."})
  (log-tool! test-logger {::tool-name "eval" ::input "(xt/q node \"SELECT * FROM ai_sessions\")"})
  (log-result! test-logger {::tool-name "eval" ::output "[{:_id \"ses-abc\" :cost 0.05}]"})
  (log-complete! test-logger {::cost 0.45 ::messages 84 ::duration-ms 100000})

  ;; Close the logger
  (close-logger! test-logger)

  ;; Test truncation
  (truncate "Hello, this is a very long string that should be truncated" 20)
  ;; => "Hello, this is a ..."

  ;; Test timestamp format
  (format-timestamp)
  ;; => "2026-01-20T13:23:20Z"

  nil)
