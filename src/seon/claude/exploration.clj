(ns seon.claude.exploration
  "Protocol exploration and capture for Claude Code bidirectional control.

   NOTE: This is EXPLORATION/RESEARCH code for REPL experimentation.
   It intentionally uses simpler patterns for quick iteration.
   Production code should follow full CONVENTIONS.md patterns.

   This namespace provides tools to:
   1. Capture ALL stdin/stdout messages for protocol analysis
   2. Explore undocumented message types
   3. Test bidirectional control (sending messages back to CLI)
   4. Document the control protocol

   Usage:
     (require '[seon.claude.exploration :as exp])

     ;; Capture a full protocol exchange
     (exp/capture-protocol! {::exp/prompt \"List files in src/\"
                             ::exp/max-turns 3})

     ;; View captured messages
     (exp/view-capture {::exp/log-file \"logs/protocol-capture-12345.jsonl\"})

     ;; Test sending control messages
     (exp/test-control-message {::exp/message-type \"interrupt\"})

   Results are logged to logs/protocol-capture-*.jsonl"
  (:require
   [cheshire.core :as json]
   [clojure.core.async :as async :refer [chan close! go-loop <! >!]]
   [clojure.java.io :as io]
   [clojure.java.process :as process]
   [clojure.string :as str]
   [seon.schema :as schema]
   [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (minimal for exploration code)
;;; ---------------------------------------------------------------------------

(schema/register! ::prompt
                  [:string {:min 1 :description "Prompt text"}])

(schema/register! ::max-turns
                  [:int {:min 1 :max 100}])

(schema/register! ::timeout-ms
                  [:int {:min 1000 :max 300000}])

(schema/register! ::log-file
                  [:string {:min 1 :description "Path to JSONL capture file"}])

(schema/register! ::message-type
                  [:string {:description "Control message type to test"}])

(schema/register! ::capture-request
                  [:map
                   [::prompt ::prompt]
                   [::max-turns {:optional true} ::max-turns]
                   [::timeout-ms {:optional true} ::timeout-ms]])

(schema/register! ::view-request
                  [:map
                   [::log-file ::log-file]])

(schema/register! ::control-test-request
                  [:map
                   [::message-type ::message-type]
                   [::prompt {:optional true} ::prompt]
                   [::payload {:optional true} :map]])

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(def ^:const default-cli-command "/opt/homebrew/bin/claude")

(def ^:const log-dir "logs")

;;; ---------------------------------------------------------------------------
;;; Logging Infrastructure
;;; ---------------------------------------------------------------------------

(defn- ensure-log-dir!
  "Ensure the log directory exists."
  []
  (let [dir (io/file log-dir)]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn- generate-capture-filename
  "Generate a unique capture filename with timestamp."
  []
  (let [ts (System/currentTimeMillis)]
    (str log-dir "/protocol-capture-" ts ".jsonl")))

(defn- log-message!
  "Log a message to a JSONL file."
  [writer direction msg]
  (let [entry {:timestamp (System/currentTimeMillis)
               :direction direction  ; :stdin or :stdout
               :message msg}]
    (.write writer (str (json/generate-string entry) "\n"))
    (.flush writer)))

;;; ---------------------------------------------------------------------------
;;; Capturing Wrapper
;;; ---------------------------------------------------------------------------

(defn- build-capture-args
  "Build CLI arguments for protocol capture."
  [{:keys [model permission-mode max-turns max-budget-usd]}]
  (cond-> [default-cli-command
           "--output-format" "stream-json"
           "--input-format" "stream-json"
           "--verbose"
           "--model" (or model "claude-opus-4-5-20251101")
           "--permission-mode" (or permission-mode "default")
           "--debug" "hooks"]  ; Enable hook debugging
    max-turns
    (into ["--max-turns" (str max-turns)])

    max-budget-usd
    (into ["--max-budget-usd" (str max-budget-usd)])))

(defn- build-env
  "Build environment for Claude CLI."
  []
  (-> (into {} (System/getenv))
      (assoc "ANTHROPIC_API_KEY" "")
      (assoc "CLAUDE_USE_SUBSCRIPTION" "true")
      (assoc "CLAUDE_CODE_ENTRYPOINT" "sdk-clj-explore")))

(defn- make-user-message
  "Create a user message for the Claude Code CLI."
  [text]
  {:type "user"
   :session_id ""
   :message {:role "user"
             :content [{:type "text" :text text}]}
   :parent_tool_use_id nil})

(defn spawn-with-capture!
  "Spawn Claude CLI with full protocol capture.

   Returns:
     {:process      Process object
      :stdin        OutputStream (for sending)
      :stdout-ch    Channel of stdout messages
      :stderr-ch    Channel of stderr lines
      :log-file     Path to JSONL capture file
      :send!        Function to send and log a message
      :close!       Function to cleanup}"
  [{:keys [cwd] :as opts}]
  (ensure-log-dir!)
  (let [log-file (generate-capture-filename)
        log-writer (io/writer log-file)
        args (build-capture-args opts)
        env (build-env)
        dir (or cwd ".")

        _ (log/info "Spawning Claude with capture" {:args args :log-file log-file})
        proc (apply process/start {:dir dir :env env} args)

        stdin (process/stdin proc)
        stdout (process/stdout proc)
        stderr (process/stderr proc)

        stdout-ch (chan 1000)
        stderr-ch (chan 100)

        ;; Stdout reader - parses JSON and logs
        stdout-reader
        (future
          (try
            (with-open [rdr (io/reader stdout)]
              (loop []
                (when-let [line (.readLine rdr)]
                  (when-not (str/blank? line)
                    (let [msg (try
                                (json/parse-string line true)
                                (catch Exception e
                                  {:type "parse_error" :raw line :error (str e)}))]
                      ;; Log to capture file
                      (log-message! log-writer :stdout msg)
                      ;; Put on channel
                      (async/>!! stdout-ch msg)))
                  (recur))))
            (catch Exception e
              (log/warn e "Stdout reader error"))
            (finally
              (close! stdout-ch))))

        ;; Stderr reader - captures debug output
        stderr-reader
        (future
          (try
            (with-open [rdr (io/reader stderr)]
              (loop []
                (when-let [line (.readLine rdr)]
                  (when-not (str/blank? line)
                    ;; Log stderr to capture file
                    (log-message! log-writer :stderr {:line line})
                    (async/>!! stderr-ch {:type "stderr" :line line}))
                  (recur))))
            (catch Exception e
              (log/warn e "Stderr reader error"))
            (finally
              (close! stderr-ch))))

        ;; Send function that logs outgoing messages
        send! (fn [msg]
                (let [json-str (str (json/generate-string msg) "\n")
                      bytes (.getBytes json-str "UTF-8")]
                  (log-message! log-writer :stdin msg)
                  (.write stdin bytes)
                  (.flush stdin)))

        ;; Cleanup function
        close! (fn []
                 (try
                   (.destroy proc)
                   (catch Exception _))
                 (try
                   (.close log-writer)
                   (catch Exception _))
                 (close! stdout-ch)
                 (close! stderr-ch))]

    {:process proc
     :stdin stdin
     :stdout-ch stdout-ch
     :stderr-ch stderr-ch
     :log-file log-file
     :send! send!
     :close! close!}))

;;; ---------------------------------------------------------------------------
;;; Protocol Capture
;;; ---------------------------------------------------------------------------

(defn capture-protocol!
  "Capture a complete protocol exchange.

   Request keys:
     ::prompt      - Required. The prompt to send
     ::max-turns   - Optional. Max turns (default: 10)
     ::timeout-ms  - Optional. Timeout in ms (default: 60000)

   Response keys:
     ::log-file    - Path to JSONL capture
     ::messages    - Vector of all stdout messages
     ::stderr      - Vector of stderr lines
     ::result      - Final result message (if any)
     ::duration-ms - Execution time

   Example:
     (capture-protocol! {::prompt \"What is 2+2?\" ::max-turns 3})"
  {:malli/schema [:=> [:cat ::capture-request] :map]}
  [{::keys [prompt max-turns timeout-ms]}]
  (let [start-time (System/currentTimeMillis)
        internal-opts {:max-turns (or max-turns 10)}
        timeout (or timeout-ms 60000)
        {:keys [stdout-ch stderr-ch log-file send! close!]}
        (spawn-with-capture! internal-opts)

        messages (atom [])
        stderr-lines (atom [])
        result (atom nil)
        done? (atom false)]

    ;; Send initial prompt
    (send! (make-user-message prompt))

    ;; Collect messages until result or timeout
    (let [deadline (+ (System/currentTimeMillis) timeout)]
      (loop []
        (when (and (not @done?)
                   (< (System/currentTimeMillis) deadline))
          (let [timeout-ch (async/timeout 100)
                [msg port] (async/alts!! [stdout-ch stderr-ch timeout-ch])]
            (cond
              ;; Stdout message
              (= port stdout-ch)
              (when msg
                (swap! messages conj msg)
                (when (= (:type msg) "result")
                  (reset! result msg)
                  (reset! done? true)))

              ;; Stderr line
              (= port stderr-ch)
              (when msg
                (swap! stderr-lines conj (:line msg)))

              ;; Timeout - check if channels closed
              :else
              nil))
          (when-not @done?
            (recur)))))

    ;; Cleanup
    (close!)

    (let [end-time (System/currentTimeMillis)]
      {::log-file log-file
       ::messages @messages
       ::stderr @stderr-lines
       ::result @result
       ::duration-ms (- end-time start-time)})))

;;; ---------------------------------------------------------------------------
;;; Analysis Tools
;;; ---------------------------------------------------------------------------

(defn view-capture
  "Load and display a protocol capture file.

   Returns a map with:
     :stdin-count   Number of stdin messages
     :stdout-count  Number of stdout messages
     :message-types Set of all message types seen
     :messages      Parsed messages"
  [log-file]
  (let [lines (line-seq (io/reader log-file))
        entries (map #(json/parse-string % true) lines)
        stdin-msgs (filter #(= :stdin (:direction %)) entries)
        stdout-msgs (filter #(= :stdout (:direction %)) entries)
        types (into #{} (map #(get-in % [:message :type]) stdout-msgs))]
    {:stdin-count (count stdin-msgs)
     :stdout-count (count stdout-msgs)
     :message-types types
     :entries entries}))

(defn analyze-message-types
  "Analyze message types from a capture.

   Returns a map of message type to:
     :count  - Number of occurrences
     :sample - First occurrence"
  [log-file]
  (let [{:keys [entries]} (view-capture log-file)
        stdout-msgs (->> entries
                         (filter #(= "stdout" (name (:direction %))))
                         (map :message))]
    (->> stdout-msgs
         (group-by :type)
         (map (fn [[t msgs]]
                [t {:count (count msgs)
                    :sample (first msgs)}]))
         (into {}))))

(defn find-tool-use-messages
  "Find all tool_use messages in a capture."
  [log-file]
  (let [{:keys [entries]} (view-capture log-file)]
    (->> entries
         (filter #(= "stdout" (name (:direction %))))
         (map :message)
         (filter #(= "assistant" (:type %)))
         (filter #(some (fn [c] (= "tool_use" (:type c)))
                        (get-in % [:message :content]))))))

;;; ---------------------------------------------------------------------------
;;; Control Message Experiments
;;; ---------------------------------------------------------------------------

(def known-message-types
  "Known message types from the protocol.

   Documented:
     - user      : User prompt
     - assistant : Claude response
     - result    : Final completion
     - system    : System/init message

   Suspected:
     - permission_request : Hook callback request?
     - interrupt          : Interrupt signal?
     - control            : Control command?"
  {:documented #{"user" "assistant" "result" "system" "keep_alive"}
   :suspected #{"permission_request" "interrupt" "control" "tool_permission"}})

(defn test-control-message
  "Experiment: send a control message and observe response.

   This is for protocol exploration - may not do anything useful!

   Options:
     :message-type - Type of control message to try
     :payload      - Additional payload

   Known experiments to try:
     - {:message-type \"interrupt\"}
     - {:message-type \"permission_response\" :payload {:approved true}}
     - {:message-type \"control\" :payload {:action \"pause\"}}"
  [{:keys [message-type payload prompt] :as opts}]
  (let [prompt (or prompt "Read CONVENTIONS.md and summarize in one sentence.")
        {:keys [stdout-ch stderr-ch send! close!]}
        (spawn-with-capture! {:max-turns 5 :permission-mode "default"})]

    ;; Send initial prompt
    (send! (make-user-message prompt))

    ;; Wait for first assistant message (tool_use)
    (println "Waiting for Claude to respond...")
    (loop [attempts 0]
      (when (< attempts 50)
        (when-let [msg (async/poll! stdout-ch)]
          (println "Got message:" (:type msg)))
        (Thread/sleep 100)
        (recur (inc attempts))))

    ;; Try sending control message
    (println "\nSending control message:" message-type)
    (let [control-msg (merge {:type message-type
                              :session_id ""
                              :timestamp (System/currentTimeMillis)}
                             (when payload {:payload payload}))]
      (send! control-msg))

    ;; Observe response
    (println "\nObserving response for 5 seconds...")
    (loop [timeout 50]
      (when (> timeout 0)
        (when-let [msg (async/poll! stdout-ch)]
          (println "Response:" (pr-str msg)))
        (when-let [err (async/poll! stderr-ch)]
          (println "Stderr:" (:line err)))
        (Thread/sleep 100)
        (recur (dec timeout))))

    (close!)
    (println "\nExperiment complete.")))

;;; ---------------------------------------------------------------------------
;;; Hook Protocol Investigation
;;; ---------------------------------------------------------------------------

(defn test-hook-callback
  "Test: Spawn with hooks configured and observe callback protocol.

   The TypeScript SDK shows that hooks receive:
     - tool_name
     - tool_input
     - tool_use_id
     - context

   And return:
     - {continue: true} to allow
     - {decision: 'block', stopReason: '...'} to block

   Question: How does this happen over stdin/stdout?"
  []
  ;; Approach 1: Look at --debug hooks output
  ;; Approach 2: Configure a hook and see what messages appear
  (let [opts {:max-turns 3
              :permission-mode "default"}  ; Not bypass - we want to see permission flow
        {:keys [stdout-ch stderr-ch log-file send! close!]}
        (spawn-with-capture! opts)]

    (println "Log file:" log-file)
    (println "Sending prompt that will trigger a tool...")

    ;; Send prompt that will use Read tool
    (send! (make-user-message "Read the file CONVENTIONS.md and tell me the first line."))

    ;; Monitor both stdout and stderr for hook-related messages
    (println "\nMonitoring for 30 seconds...")
    (loop [timeout 300]
      (when (> timeout 0)
        (when-let [msg (async/poll! stdout-ch)]
          (println "STDOUT:" (:type msg)
                   (when (contains? msg :subtype) (str "(" (:subtype msg) ")"))
                   (when (contains? msg :tool_use_id) (str "[" (:tool_use_id msg) "]")))
          ;; Print full message for interesting types
          (when (not (contains? #{"keep_alive" "assistant" "user"} (:type msg)))
            (println "  Full:" (pr-str msg))))

        (when-let [err (async/poll! stderr-ch)]
          (println "STDERR:" (:line err)))

        (Thread/sleep 100)
        (recur (dec timeout))))

    (close!)
    (println "\nCapture saved to:" log-file)))

;;; ---------------------------------------------------------------------------
;;; CLI Exploration
;;; ---------------------------------------------------------------------------

(defn list-cli-flags
  "Attempt to discover CLI flags by running --help."
  []
  (let [proc (process/start {} default-cli-command "--help")
        stdout (slurp (process/stdout proc))
        stderr (slurp (process/stderr proc))]
    (process/exit-ref proc)
    {:stdout stdout
     :stderr stderr}))

(defn test-debug-flags
  "Test various debug flags to see what extra output they produce.

   Known flags:
     --debug hooks     - Debug hook execution
     --debug mcp       - Debug MCP communication
     --debug all       - All debug output"
  [debug-mode]
  (let [args [default-cli-command
              "--output-format" "stream-json"
              "--input-format" "stream-json"
              "--verbose"
              "--debug" debug-mode
              "--max-turns" "2"
              "--permission-mode" "bypassPermissions"]
        env (build-env)
        proc (apply process/start {:env env} args)

        stdout (process/stdout proc)
        stderr (process/stderr proc)
        stdin (process/stdin proc)]

    ;; Send a simple prompt
    (let [msg (make-user-message "What is 1+1?")
          json-str (str (json/generate-string msg) "\n")]
      (.write stdin (.getBytes json-str "UTF-8"))
      (.flush stdin))

    ;; Collect stderr (where debug goes)
    (println "Debug output for --debug" debug-mode ":")
    (println "=" (apply str (repeat 50 "=")))

    ;; Wait a bit for output
    (Thread/sleep 3000)

    (with-open [rdr (io/reader stderr)]
      (loop []
        (when-let [line (.readLine rdr)]
          (println line)
          (recur))))

    (.destroy proc)))

;;; ---------------------------------------------------------------------------
;;; Multi-Turn Session (AsyncIterable Pattern)
;;; ---------------------------------------------------------------------------

(defn start-multi-turn-session!
  "Start a multi-turn session using the AsyncIterable pattern.

   This mirrors the TypeScript SDK pattern where prompt can be an AsyncIterable.
   We use a core.async channel as our 'AsyncIterable' equivalent.

   Returns a session map with:
     :messages-ch   - Channel to receive stdout messages
     :send!         - Function to send user messages
     :close!        - Function to close session
     :log-file      - Path to capture file
     :session-id    - Atom that will hold Claude's session ID

   Usage:
     (def session (start-multi-turn-session! {:max-turns 10}))
     ((:send! session) \"Hello!\")
     ;; Read messages from (:messages-ch session)
     ((:send! session) \"Follow up question\")
     ((:close! session))"
  ([] (start-multi-turn-session! {}))
  ([{:keys [max-turns model] :or {max-turns 100 model "sonnet"}}]
   (let [{:keys [stdout-ch stderr-ch send! close! log-file]}
         (spawn-with-capture! {:max-turns max-turns
                               :model model
                               :permission-mode "bypassPermissions"})
         session-id-atom (atom nil)
         messages-ch (chan 1000)]

     ;; Forward stdout messages and capture session ID
     (go-loop []
       (when-let [msg (<! stdout-ch)]
         ;; Capture session ID from init message
         (when (and (= "system" (:type msg))
                    (= "init" (:subtype msg)))
           (reset! session-id-atom (:session_id msg))
           (log/info "Captured session ID" {:session-id (:session_id msg)}))

         (>! messages-ch msg)
         (recur)))

     ;; Return session handle
     {:messages-ch messages-ch
      :stderr-ch stderr-ch
      :send! (fn [text]
               (send! (make-user-message text)))
      :close! close!
      :log-file log-file
      :session-id session-id-atom})))

(defn read-until-result!
  "Read messages from a session until we see a result.

   Returns:
     {:messages [...]  ; All messages received
      :result   {...}  ; The result message}"
  [session & {:keys [timeout-ms] :or {timeout-ms 60000}}]
  (let [messages (atom [])
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if (> (System/currentTimeMillis) deadline)
        {:messages @messages
         :result {:type "timeout"}}
        (let [timeout-ch (async/timeout 100)
              [msg port] (async/alts!! [(:messages-ch session) timeout-ch])]
          (if (= port (:messages-ch session))
            (do
              (swap! messages conj msg)
              (if (= "result" (:type msg))
                {:messages @messages
                 :result msg}
                (recur)))
            (recur)))))))

(defn multi-turn-conversation!
  "Run a multi-turn conversation and capture the full exchange.

   Example:
     (multi-turn-conversation!
       [\"What is 5+3?\"
        \"Multiply that by 2\"
        \"Is the result greater than 10?\"])

   Returns:
     {:turns [{:prompt \"...\" :messages [...] :result {...}} ...]
      :session-id \"...\"
      :log-file \"...\"}"
  [prompts & {:keys [max-turns model] :or {max-turns 50 model "sonnet"}}]
  (let [session (start-multi-turn-session! {:max-turns max-turns :model model})
        turns (atom [])]

    (doseq [prompt prompts]
      (log/info "Sending prompt" {:prompt prompt})
      ((:send! session) prompt)
      (let [{:keys [messages result]} (read-until-result! session)]
        (swap! turns conj {:prompt prompt
                           :messages messages
                           :result result})))

    ((:close! session))

    {:turns @turns
     :session-id @(:session-id session)
     :log-file (:log-file session)}))

;;; ---------------------------------------------------------------------------
;;; Session Resume Testing
;;; ---------------------------------------------------------------------------

(defn test-session-resume!
  "Test the session resume functionality.

   1. Start a session
   2. Establish context (tell Claude something to remember)
   3. Close the session
   4. Resume with --resume flag
   5. Verify context is preserved

   Returns analysis of the resume behavior."
  []
  (log/info "Testing session resume...")

  ;; Phase 1: Establish context
  (let [session1 (start-multi-turn-session! {:max-turns 5 :model "sonnet"})]
    ((:send! session1) "My favorite color is blue. Remember this!")
    (let [{:keys [result]} (read-until-result! session1)
          session-id @(:session-id session1)]
      ((:close! session1))

      (log/info "Phase 1 complete" {:session-id session-id})

      ;; Phase 2: Resume and verify
      (Thread/sleep 1000)  ; Brief pause

      (let [args [default-cli-command
                  "--output-format" "stream-json"
                  "--input-format" "stream-json"
                  "--verbose"
                  "--model" "sonnet"
                  "--permission-mode" "bypassPermissions"
                  "--resume" session-id
                  "--max-turns" "3"]
            env (build-env)
            proc (apply process/start {:env env} args)
            stdin (process/stdin proc)
            stdout (process/stdout proc)
            messages (atom [])]

        (log/info "Phase 2: Resuming session" {:session-id session-id})

        ;; Start reader
        (future
          (with-open [rdr (io/reader stdout)]
            (loop []
              (when-let [line (.readLine rdr)]
                (when-not (str/blank? line)
                  (let [msg (json/parse-string line true)]
                    (swap! messages conj msg)))
                (recur)))))

        ;; Ask about the context
        (let [msg (make-user-message "What is my favorite color?")
              json-str (str (json/generate-string msg) "\n")]
          (.write stdin (.getBytes json-str "UTF-8"))
          (.flush stdin))

        ;; Wait for result
        (Thread/sleep 10000)
        (.destroy proc)

        {:original-session-id session-id
         :resumed-messages @messages
         :context-preserved? (some #(str/includes? (str %) "blue") @messages)}))))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  ;; ==========================================================================
  ;; 0. Multi-Turn Session (NEW - from TypeScript SDK pattern)
  ;; ==========================================================================

  ;; Simple multi-turn conversation
  (def conversation
    (multi-turn-conversation!
     ["What is 5+3?"
      "Multiply that by 2"
      "Is the result greater than 10?"]))

  (:turns conversation)
  (:session-id conversation)

  ;; View the capture log
  (view-capture (:log-file conversation))

  ;; Test session resume
  (test-session-resume!)

  ;; Interactive multi-turn session
  (def session (start-multi-turn-session! {:max-turns 20}))
  ((:send! session) "Hello! Remember my name is Sean.")
  (def r1 (read-until-result! session))
  (:result r1)

  ((:send! session) "What is my name?")
  (def r2 (read-until-result! session))
  (:result r2)

  ((:close! session))

  ;; ==========================================================================
  ;; 1. Basic Protocol Capture
  ;; ==========================================================================

  ;; Capture a simple query
  (def capture
    (capture-protocol!
     {:prompt "What is 2+2? Reply with just the number."
      :max-turns 3
      :timeout-ms 30000
      :on-message #(println "MSG:" (:type %))}))

  ;; View results
  (:log-file capture)
  (:messages capture)
  (:result capture)

  ;; ==========================================================================
  ;; 2. Analyze Captured Protocol
  ;; ==========================================================================

  ;; View a capture file
  (view-capture "logs/protocol-capture-1234567890.jsonl")

  ;; Analyze message types
  (analyze-message-types "logs/protocol-capture-1234567890.jsonl")

  ;; Find tool_use messages
  (find-tool-use-messages "logs/protocol-capture-1234567890.jsonl")

  ;; ==========================================================================
  ;; 3. Hook Protocol Investigation
  ;; ==========================================================================

  ;; Run hook callback test
  (test-hook-callback)

  ;; ==========================================================================
  ;; 4. Control Message Experiments
  ;; ==========================================================================

  ;; Try sending interrupt
  (test-control-message {:message-type "interrupt"})

  ;; Try permission response
  (test-control-message {:message-type "permission_response"
                         :payload {:approved false}})

  ;; ==========================================================================
  ;; 5. CLI Exploration
  ;; ==========================================================================

  ;; Get help text
  (list-cli-flags)

  ;; Test debug modes
  (test-debug-flags "hooks")
  (test-debug-flags "mcp")
  (test-debug-flags "all")

  nil)
