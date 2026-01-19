(ns seon.ai.agent
  "Provider-agnostic agent extension points.

   This namespace defines multimethods that AI providers implement to integrate
   with the Seon agent framework. Each provider (Claude, Gemini, etc.) implements
   these methods to handle their specific message formats while presenting a
   unified interface for agent lifecycle management.

   ## Multimethods

   - `normalize-message` - Convert provider-specific message to ::ai/message entity
   - `result-message?` - Check if a message is the final result message
   - `parse-result` - Extract final stats from a result message

   ## Dispatch

   All multimethods dispatch on the `:provider` key in the request map:

     {:provider :claude ...}  -> dispatches to Claude implementation
     {:provider :gemini ...}  -> dispatches to Gemini implementation

   ## Usage

   Provider namespaces implement these methods:

     (defmethod agent/normalize-message :claude
       [{:keys [message session-id]}]
       (sdk-message->entity {...}))

   The agent lifecycle (launch-agent!, etc.) remains in provider namespaces
   until we have multiple providers that need shared orchestration.

   Example:

     (require '[seon.ai.agent :as agent])
     (require '[seon.ai.claude]) ; Load Claude implementations

     ;; Normalize a Claude message
     (agent/normalize-message {:provider :claude
                               :message sdk-msg
                               :session-id \"ses-abc123\"})

     ;; Check if it's the final result
     (agent/result-message? {:provider :claude
                             :message sdk-msg})

     ;; Extract result stats
     (agent/parse-result {:provider :claude
                          :message result-msg})"
  (:require [seon.schema :as schema]))

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

(comment
  ;; REPL exploration

  ;; View registered schemas
  (require '[seon.schema :as schema])
  (schema/schemas-in-namespace "seon.ai.agent")

  ;; Generate sample parsed-result
  (require '[malli.generator :as mg])
  (mg/generate ::parsed-result)

  ;; Validate result
  (require '[malli.core :as m])
  (m/validate ::parsed-result {::status :completed
                               ::cost-usd 0.05
                               ::num-turns 10})

  nil)
