(ns seon.ai.agent
  "Provider-agnostic agent extension points and registry.

   This namespace defines:
   1. Multimethods that AI providers implement for message handling
   2. Centralized agent registry for cross-provider observability
   3. Observatory functions (agents, tail, interrupt!, get-agent)

   ## Multimethods (implemented by providers)

   - `normalize-message` - Convert provider-specific message to ::ai/message entity
   - `result-message?` - Check if a message is the final result message
   - `parse-result` - Extract final stats from a result message

   ## Agent Registry

   All agent providers register their running agents here:

     (swap! agent-registry assoc session-id handle)
     (swap! agent-registry dissoc session-id)

   ## Observatory API

   Provider-agnostic functions for observing agents:

   - `(agents {})` - List all running agents across providers
   - `(get-agent {::session-id \"a1b2\"})` - Get agent handle by ID
   - `(tail {::session-id \"a1b2\"})` - Get messages channel
   - `(interrupt! {::session-id \"a1b2\"})` - Stop an agent

   ## Dispatch

   All multimethods dispatch on the `:provider` key in the request map:

     {:provider :claude ...}  -> dispatches to Claude implementation
     {:provider :gemini ...}  -> dispatches to Gemini implementation

   ## Usage

   Provider namespaces implement multimethods and use the shared registry:

     ;; In provider namespace (e.g., seon.ai.claude)
     (defmethod agent/normalize-message :claude
       [{:keys [message session-id]}]
       (sdk-message->entity {...}))

     ;; Register agent after launch
     (swap! agent/agent-registry assoc session-id handle)

   Example:

     (require '[seon.ai.agent :as agent])
     (require '[seon.ai.claude]) ; Load Claude implementations

     ;; List all running agents
     (agent/agents {})

     ;; Stream messages from an agent
     (agent/tail {::session-id \"a1b2\"})

     ;; Interrupt an agent
     (agent/interrupt! {::session-id \"a1b2\"})"
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [seon.ai :as ai]
            [seon.ns.view :as view]
            [seon.runtime :as runtime]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

;; Parsed result from a completed agent run
(schema/register! ::parsed-result
                  [:map {:description "Parsed result from agent completion"}
                   [::status [:enum :completed :failed :interrupted :error]]
                   [::cost-usd {:optional true} [:double {:min 0.0}]]
                   [::input-tokens {:optional true} [:int {:min 0}]]
                   [::output-tokens {:optional true} [:int {:min 0}]]
                   [::num-turns {:optional true} [:int {:min 0}]]
                   [::duration-ms {:optional true} [:int {:min 0}]]
                   [::subtype {:optional true} :string]
                   [::result-text {:optional true} :string]])

;; Agent status enum (generic across providers)
(schema/register! ::agent-status
                  [:enum {:description "Agent runtime status"}
                   :running :completed :failed :terminated :interrupted])

;; Session ID reference (reuses ::ai/session-id pattern)
(schema/register! ::session-id
                  [:string {:min 1
                            :description "Agent session ID (4-char hex for Seon agents)"}])

;; Namespace reference (reuses ::ai/namespace pattern)
(schema/register! ::namespace
                  [:string {:min 1
                            :description "Agent namespace"}])

;; Provider identifier for agents
(schema/register! ::provider
                  [:enum {:description "AI provider"}
                   :claude :gemini :openai :local])

;; Agent summary for list views (generic structure)
(schema/register! ::agent-summary
                  [:map {:description "Agent summary for list views"
                         :seon/view :seon.ai.agent/summary}
                   [::session-id ::session-id]
                   [::namespace ::namespace]
                   [::provider ::provider]
                   [::agent-status ::agent-status]
                   ;; Optional provider-specific fields
                   [::nrepl-port {:optional true} :int]
                   [::ai-session-id {:optional true} :string]
                   [::cost-usd {:optional true} [:double {:min 0.0}]]])

;; Interrupt request
(schema/register! ::interrupt-request
                  [:map
                   [::session-id ::session-id]])

;; Interrupt response
(schema/register! ::interrupt-response
                  [:map
                   [::session-id ::session-id]
                   [::interrupted? :boolean]
                   [::method {:optional true} [:enum :sigint :destroy :close]]
                   [::error {:optional true} :string]])

;; Get agent request
(schema/register! ::get-agent-request
                  [:map
                   [::session-id ::session-id]])

;; Tail request
(schema/register! ::tail-request
                  [:map
                   [::session-id ::session-id]])

;; Agents (list) request/response
(schema/register! ::agents-request
                  [:map])

(schema/register! ::agents-response
                  [:vector ::agent-summary])

;;; ---------------------------------------------------------------------------
;;; Multimethods
;;; ---------------------------------------------------------------------------

(defmulti normalize-message
  "Convert a provider-specific message to a ::ai/message entity.

   Takes a map with:
     :provider   - Provider keyword (:claude, :gemini, etc.)
     :message    - Provider-specific message map
     :session-id - Optional. AI session ID to attach to the entity

   Returns a map suitable for Datalevin storage with:
     :seon/id            - Generated message ID
     :seon.ai/type     - :message
     :seon.ai/role     - \"user\", \"assistant\", or \"system\"
     :seon.ai/content  - Text content
     :seon.ai/timestamp - When the message was received
     Plus provider-specific attributes under their namespace.

   Dispatch: (fn [{:keys [provider]}] provider)

   Example:
     (normalize-message {:provider :claude
                         :message {:type \"assistant\" :message {...}}
                         :session-id \"ses-abc123\"})"
  :provider)

(defmulti result-message?
  "Check if a message is the final result message from the agent.

   Takes a map with:
     :provider - Provider keyword (:claude, :gemini, etc.)
     :message  - Provider-specific message map

   Returns true if this message indicates the agent has completed
   (successfully or with an error).

   Dispatch: (fn [{:keys [provider]}] provider)

   Example:
     (result-message? {:provider :claude
                       :message {:type \"result\" :subtype \"success\" ...}})"
  :provider)

(defmulti parse-result
  "Extract final stats from a result message.

   Takes a map with:
     :provider - Provider keyword (:claude, :gemini, etc.)
     :message  - Provider-specific result message

   Returns a map with:
     ::status       - :completed, :failed, :interrupted, or :error
     ::cost-usd     - Optional. Total cost in USD
     ::input-tokens - Optional. Total input tokens
     ::output-tokens - Optional. Total output tokens
     ::num-turns    - Optional. Number of conversation turns
     ::duration-ms  - Optional. Total duration in milliseconds
     ::subtype      - Optional. Provider-specific result subtype
     ::result-text  - Optional. Final result text

   Dispatch: (fn [{:keys [provider]}] provider)

   Example:
     (parse-result {:provider :claude
                    :message {:type \"result\"
                              :subtype \"success\"
                              :total_cost_usd 0.05
                              :num_turns 10}})"
  :provider)

;;; ---------------------------------------------------------------------------
;;; Default Implementations
;;; ---------------------------------------------------------------------------

(defmethod normalize-message :default
  [{:keys [provider]}]
  (throw (ex-info (str "No normalize-message implementation for provider: " provider)
                  {:provider provider})))

(defmethod result-message? :default
  [{:keys [provider]}]
  (throw (ex-info (str "No result-message? implementation for provider: " provider)
                  {:provider provider})))

(defmethod parse-result :default
  [{:keys [provider]}]
  (throw (ex-info (str "No parse-result implementation for provider: " provider)
                  {:provider provider})))

;;; ---------------------------------------------------------------------------
;;; Agent Registry
;;; ---------------------------------------------------------------------------

;; Global agent registry. Maps session-id to agent handle.
;;
;; Agent handles must include at minimum:
;;   - ::session-id - The session identifier
;;   - ::namespace - Agent namespace (string)
;;   - ::provider - Provider keyword (:claude, :gemini, etc.)
;;   - ::status-atom - Atom containing current status
;;   - ::close! - Function to terminate the agent
;;   - ::messages-ch - core.async channel of messages (for tail)
;;
;; Providers may add additional fields (e.g., ::nrepl-port for Claude).
(defonce agent-registry (atom {}))

;;; ---------------------------------------------------------------------------
;;; Observatory API
;;; ---------------------------------------------------------------------------

(defn agents
  "List all running agents with status, namespace, provider.

   Request keys:
     (none - empty map for consistency)

   Response keys (vector of):
     ::session-id       - Agent session ID
     ::namespace        - Agent namespace
     ::provider         - Provider keyword (:claude, :gemini)
     ::agent-status     - Current status (:running, :completed, :failed, :terminated)
     ::nrepl-port       - Optional. nREPL port for direct REPL access
     ::ai-session-id    - Optional. AI conversation session ID
     ::last-activity-at - Optional. Instant of last message received
     ::process-alive?   - Optional. Whether the Java Process is still alive

   Example:
     (agents {})
     ;; => [{:seon.ai.agent/session-id \"a1b2\"
     ;;      :seon.ai.agent/namespace \"seon.trading\"
     ;;      :seon.ai.agent/provider :claude
     ;;      :seon.ai.agent/agent-status :running
     ;;      :seon.ai.agent/nrepl-port 7889
     ;;      :seon.ai.agent/process-alive? true
     ;;      :seon.ai.agent/last-activity-at #inst \"...\"}]"
  [_request]
  (vec (for [[id handle] @agent-registry]
         (cond-> {::session-id id
                  ::namespace (::namespace handle)
                  ::provider (::provider handle)
                  ::agent-status @(::status-atom handle)}
           (::nrepl-port handle)
           (assoc ::nrepl-port (::nrepl-port handle))
           (::ai-session-id handle)
           (assoc ::ai-session-id (::ai-session-id handle))
           (::last-activity-at handle)
           (assoc ::last-activity-at @(::last-activity-at handle))
           (::process handle)
           (assoc ::process-alive? (.isAlive ^Process (::process handle)))))))

(defn get-agent
  "Get an agent handle by session ID.

   Request keys:
     ::session-id - Required. The session ID

   Returns:
     The full agent handle map, or nil if not found.

   Example:
     (get-agent {::session-id \"a1b2\"})"
  [{::keys [session-id]}]
  (get @agent-registry session-id))

(defn tail
  "Get the messages channel from a running agent.

   Use this to observe an agent's activity in real-time.

   Request keys:
     ::session-id - Required. The session ID

   Returns:
     A core.async channel of provider-specific messages, or nil if agent not found.

   Example:
     (let [ch (tail {::session-id \"a1b2\"})]
       (go-loop []
         (when-let [msg (<! ch)]
           (println \"Agent says:\" (:type msg))
           (recur))))

   Note: This returns the messages channel, which may be shared.
   Closing this channel will affect the agent."
  [{::keys [session-id]}]
  (when-let [handle (get @agent-registry session-id)]
    (::messages-ch handle)))

(defn interrupt!
  "Interrupt a running agent.

   Attempts to stop the agent by calling its close! function.
   This is provider-agnostic - the close! function handles provider-specific
   cleanup (process destruction, channel closing, session ending).

   Request keys:
     ::session-id - Required. The session ID

   Response keys:
     ::session-id   - The session that was interrupted
     ::interrupted? - Whether the interrupt succeeded
     ::method       - Method used (:close)
     ::error        - Error message if failed

   Example:
     (interrupt! {::session-id \"a1b2\"})
     ;; => {:seon.ai.agent/session-id \"a1b2\"
     ;;     :seon.ai.agent/interrupted? true
     ;;     :seon.ai.agent/method :close}"
  [{::keys [session-id]}]
  (if-let [handle (get @agent-registry session-id)]
    (let [status-atom (::status-atom handle)
          close! (::close! handle)]
      (if close!
        (try
          (close!)
          (reset! status-atom :interrupted)
          (log/info "Interrupted agent" {:session-id session-id
                                          :provider (::provider handle)})
          {::session-id session-id
           ::interrupted? true
           ::method :close}
          (catch Exception e
            (log/warn e "Failed to interrupt agent" {:session-id session-id})
            {::session-id session-id
             ::interrupted? false
             ::error (.getMessage e)}))
        ;; No close! function provided
        {::session-id session-id
         ::interrupted? false
         ::error "Agent handle does not have a close! function"}))
    ;; Agent not found
    {::session-id session-id
     ::interrupted? false
     ::error "Agent not found in registry"}))

(defn shutdown-all!
  "Shut down all running agents in the registry.

   This is called before integrant reset to prevent core.async protocol
   corruption and orphaned nREPL servers. When namespaces are reloaded
   while channels are open, the old channel instances don't recognize
   the reloaded protocols.

   This function:
   1. Calls each agent's close! function (stops process, channels, session)
   2. As a safety net, also calls stop-all-namespace-nrepls! to ensure
      no nREPL servers survive even if close! fails

   Request keys:
     (none - empty map for consistency)

   Response keys:
     ::shutdown-count - Number of agents that were shut down
     ::nrepl-count    - Number of nREPL servers stopped (safety net)
     ::errors         - Vector of errors encountered (may be empty)

   Example:
     (shutdown-all! {})
     ;; => {:seon.ai.agent/shutdown-count 3
     ;;     :seon.ai.agent/nrepl-count 0
     ;;     :seon.ai.agent/errors []}"
  [_request]
  (let [agents @agent-registry
        errors (atom [])]
    ;; Step 1: Call each agent's close! function
    (doseq [[id handle] agents]
      (try
        (when-let [close! (::close! handle)]
          (log/info "Shutting down agent for reset" {:session-id id})
          (close!))
        (catch Exception e
          (log/warn e "Error shutting down agent" {:session-id id})
          (swap! errors conj {:session-id id :error (.getMessage e)}))))
    ;; Clear registry in case close! didn't remove entries
    (reset! agent-registry {})

    {::shutdown-count (count agents)
     ::errors @errors}))

;;; ---------------------------------------------------------------------------
;;; Render Support
;;; ---------------------------------------------------------------------------

(defn- read-agent-log
  "Read the last N lines from an agent's log file."
  [agent-id max-lines]
  (if-not (runtime/session-id? agent-id)
    []
    (let [log-path (str "logs/agents/" agent-id ".log")
          f (io/file log-path)]
      (if (.exists f)
        (try
          (let [result (shell/sh "tail" "-n" (str max-lines) log-path)]
            (if (zero? (:exit result))
              (str/split-lines (:out result))
              []))
          (catch Exception _ []))
        []))))

(defn- parse-log-line
  "Parse an agent log line into structured data.
   Format: timestamp | TYPE | field1 | field2 | ...

   Returns map with :type and type-specific fields:
   - LAUNCH: :namespace, :port
   - MESSAGE: :role, :content
   - TOOL: :tool-name, :input
   - RESULT: :tool-name, :output
   - HOOK: :file-type, :tests-status, :gemini-status
   - COMPLETE: :subtype, :cost, :messages, :duration-ms
   - ERROR: :error"
  [line]
  (when (and line (string? line) (not (str/blank? line)))
    (let [parts (str/split line #" \| ")
          timestamp (first parts)
          log-type (when (second parts) (str/trim (second parts)))]
      (when log-type
        (case log-type
          "LAUNCH"
          (let [[_ _ ns-str port-str] parts]
            {:type "LAUNCH"
             :timestamp timestamp
             :namespace ns-str
             :port (when port-str
                     (when-let [[_ p] (re-find #"port=(\d+)" port-str)]
                       (parse-long p)))})

          "MESSAGE"
          (let [[_ _ role content] parts]
            {:type "MESSAGE"
             :timestamp timestamp
             :role role
             :content (when content (str/replace content #"^\"|\"$" ""))})

          "TOOL"
          (let [[_ _ tool-name input] parts]
            {:type "TOOL"
             :timestamp timestamp
             :tool-name tool-name
             :input input})

          "RESULT"
          (let [[_ _ tool-id output] parts]
            {:type "RESULT"
             :timestamp timestamp
             :tool-name tool-id
             :output output})

          "HOOK"
          (let [details (str/join " | " (drop 2 parts))
                tests (second (re-find #"tests=(\w+)" details))
                gemini (second (re-find #"gemini=(\w+)" details))]
            {:type "HOOK"
             :timestamp timestamp
             :file-type (nth parts 2 nil)
             :tests-status tests
             :gemini-status gemini})

          "COMPLETE"
          (let [details (str/join " | " (drop 2 parts))
                subtype (second (re-find #"subtype=(\w+)" details))
                cost (when-let [c (second (re-find #"cost=\$?([\d.]+)" details))]
                       (parse-double c))
                msgs (when-let [m (second (re-find #"messages=(\d+)" details))]
                       (parse-long m))
                dur (when-let [d (second (re-find #"duration=(\d+)" details))]
                      (parse-long d))]
            {:type "COMPLETE"
             :timestamp timestamp
             :subtype subtype
             :cost cost
             :messages msgs
             :duration-ms dur})

          "ERROR"
          {:type "ERROR"
           :timestamp timestamp
           :error (str/join " | " (drop 2 parts))}

          ;; Unknown type - return basic structure
          {:type log-type
           :timestamp timestamp
           :details (str/join " | " (drop 2 parts))})))))

(defn- completed-sessions
  "Get recent completed/failed sessions from Datalevin."
  [limit]
  (ai/list-sessions {::ai/limit limit}))

(defn- running-agent-ids
  "Get set of AI session IDs for running agents."
  []
  (set (keep ::ai-session-id (vals @agent-registry))))

(defn- all-agents-data
  "Get combined list of running agents and completed sessions for list view."
  []
  (let [running (agents {})
        running-ids (running-agent-ids)
        completed (->> (completed-sessions 50)
                       (remove #(running-ids (:seon/id %)))
                       (filter ::ai/agent-session-id))]
    {:running running
     :completed completed}))

(defn- agent-detail-data
  "Get agent detail data including log lines."
  [agent-id]
  (let [;; Check running agents first
        running (first (filter #(= agent-id (::session-id %)) (agents {})))
        ;; Get log lines
        log-lines (->> (read-agent-log agent-id 200)
                       (keep parse-log-line))]
    (when (or running (seq log-lines))
      (cond-> {::session-id agent-id
               ::agent-status (or (::agent-status running) :completed)
               ::log-lines log-lines}
        running (merge (select-keys running [::namespace ::nrepl-port ::ai-session-id]))))))

;;; ---------------------------------------------------------------------------
;;; Render Function (called by seon.ns.routes convention)
;;; ---------------------------------------------------------------------------

(defn- typed-value
  "Helper to create typed value for rendering.
   Wraps view/typed with positional args for internal use."
  [view-type value]
  (view/typed {::view/view-type view-type ::view/value value}))

(defn render
  "Render agent view in the requested format.

   Called by seon.ns.routes when visiting /ns/seon.ai.agent

   Params:
     :format - :html, :ai, :human, or :raw
     :id     - Optional agent session ID for detail view

   Returns rendered content in the requested format."
  [{:keys [format id]}]
  ;; Ensure views are loaded
  (require 'seon.ai.agent.views)
  (if id
    ;; Detail view
    (if-let [data (agent-detail-data id)]
      (view/render (typed-value :seon.ai.agent/detail data) format)
      [:div {:class "text-center py-12 text-text-400"}
       "Agent not found: " [:code {:class "text-signal"} id]])
    ;; List view
    (let [{:keys [running completed]} (all-agents-data)
          running-count (count running)
          completed-count (count completed)]
      [:div
       ;; Header
       [:div {:class "flex items-center justify-between mb-4"}
        [:div
         [:h1 {:class "text-base font-bold tracking-tight"} "Agent Observatory"]
         [:p {:class "text-text-400 text-xs mt-0.5"}
          (str running-count " running"
               (when (pos? completed-count)
                 (str " · " completed-count " completed")))]]]
       ;; Table
       [:div {:class "bg-base-850 rounded overflow-hidden"}
        [:table {:class "w-full"}
         [:thead
          [:tr {:class "border-b border-base-700"}
           [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"} "ID"]
           [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"} "Namespace"]
           [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"} "Status"]
           [:th {:class "text-right py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"} "Port"]
           [:th {:class "text-right py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"} "Cost"]]]
         [:tbody
          (if (and (empty? running) (empty? completed))
            [:tr
             [:td {:class "py-8 px-4 text-center text-text-500 italic" :colspan "5"}
              "No agents found"]]
            (concat
             ;; Running agents
             (for [agent running]
               (view/render (typed-value :seon.ai.agent/summary agent) format))
             ;; Completed sessions
             (for [session completed
                   :let [agent-sid (::ai/agent-session-id session)]]
               (view/render (typed-value :seon.ai.agent/summary
                                         {::session-id agent-sid
                                          ::namespace (::ai/namespace session)
                                          ::provider :claude
                                          ::agent-status (::ai/status session)
                                          ::cost-usd (::ai/cost-usd session)})
                            format))))]]]])))

(comment
  ;; REPL exploration

  ;; View registered schemas
  (require '[seon.schema :as schema])
  (schema/schemas-in-namespace "seon.ai.agent")

  ;; Generate sample parsed-result
  (require '[malli.generator :as mg])
  (mg/generate ::parsed-result)
  (mg/generate ::agent-summary)
  (mg/generate ::agent-status)

  ;; Validate result
  (require '[malli.core :as m])
  (m/validate ::parsed-result {::status :completed
                               ::cost-usd 0.05
                               ::num-turns 10})

  ;; Observatory API examples
  (agents {})                           ; List all running agents
  (get-agent {::session-id "a1b2"})     ; Get handle by ID
  (tail {::session-id "a1b2"})          ; Get messages channel
  (interrupt! {::session-id "a1b2"})    ; Stop an agent

  ;; Manual registry inspection
  @agent-registry

  nil)
