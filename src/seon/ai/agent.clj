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
  (:require [seon.ai :as ai]
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
                  [:map {:description "Agent summary for list views"}
                   [::session-id ::session-id]
                   [::namespace ::namespace]
                   [::provider ::provider]
                   [::agent-status ::agent-status]
                   ;; Optional provider-specific fields
                   [::nrepl-port {:optional true} :int]
                   [::ai-session-id {:optional true} :string]])

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

   Returns a map suitable for XTDB storage with:
     :xt/id            - Generated message ID
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
     ::session-id    - Agent session ID
     ::namespace     - Agent namespace
     ::provider      - Provider keyword (:claude, :gemini)
     ::agent-status  - Current status (:running, :completed, :failed, :terminated)
     ::nrepl-port    - Optional. nREPL port for direct REPL access
     ::ai-session-id - Optional. AI conversation session ID

   Example:
     (agents {})
     ;; => [{:seon.ai.agent/session-id \"a1b2\"
     ;;      :seon.ai.agent/namespace \"seon.trading\"
     ;;      :seon.ai.agent/provider :claude
     ;;      :seon.ai.agent/agent-status :running
     ;;      :seon.ai.agent/nrepl-port 7889}]"
  [_request]
  (vec (for [[id handle] @agent-registry]
         (cond-> {::session-id id
                  ::namespace (::namespace handle)
                  ::provider (::provider handle)
                  ::agent-status @(::status-atom handle)}
           (::nrepl-port handle)
           (assoc ::nrepl-port (::nrepl-port handle))
           (::ai-session-id handle)
           (assoc ::ai-session-id (::ai-session-id handle))))))

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
   corruption. When namespaces are reloaded while channels are open,
   the old channel instances don't recognize the reloaded protocols.

   Request keys:
     (none - empty map for consistency)

   Response keys:
     ::shutdown-count - Number of agents that were shut down
     ::errors         - Vector of errors encountered (may be empty)

   Example:
     (shutdown-all! {})
     ;; => {:seon.ai.agent/shutdown-count 3
     ;;     :seon.ai.agent/errors []}"
  [_request]
  (let [agents @agent-registry
        errors (atom [])]
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
