(ns seon.ai.openai-compat.core
  "Pure OpenAI-compatible request and response transforms."
  (:require
   [clojure.string :as str]
   [seon.ai.core :as ai]
   [seon.ai.tokens :as tokens]))

(def ^:private known-completion-keys
  #{:choices :usage :id :object :created :model :system_fingerprint})

(defn- openai-compat?
  [resolution]
  (= :openai-compat
     (get-in resolution [:seon.ai/resolved-config :seon.ai/provider])))

(defn request-params
  "Build OpenAI chat-completion wire data from one frozen resolution."
  {:malli/schema
   [:=> [:catn [::request :map] [::resolution :map]] :map]}
  [{:seon.ai/keys [ctx model temperature max-tokens tools tool-choice stream?]
    :as request}
   resolution]
  (let [config (:seon.ai/resolved-config resolution)
        thinking (ai/thinking-mode config)
        compatible? (openai-compat? resolution)
        temperature* (or temperature (:seon.ai/temperature config))
        tools* (or tools (:seon.ai/tools config))
        choice* (or tool-choice (:seon.ai/tool-choice config))
        completion-limit-key
        (case (:seon.ai/completion-limit-field config)
          :max-completion-tokens :max_completion_tokens
          :max_tokens)]
    (cond->
     {:model (or model (:seon.ai/model config))
      :messages [{:role "system"
                  :content (:seon.ai/system-prompt request)}
                 {:role "user" :content ctx}]}
      (some? (or max-tokens (:seon.ai/max-tokens config)))
      (assoc completion-limit-key
             (or max-tokens (:seon.ai/max-tokens config)))

      stream?
      (assoc :stream true :stream_options {:include_usage true})

      (some? temperature*)
      (assoc :temperature temperature*)

      (not compatible?)
      (assoc :thinking {:type (if thinking "enabled" "disabled")})

      (string? thinking)
      (assoc :reasoning_effort thinking)

      (some? tools*)
      (assoc :tools tools*)

      (some? choice*)
      (assoc :tool_choice choice*))))

(defn empty-content-with-reasoning?
  "True when only provider-private reasoning content is present."
  {:malli/schema [:=> [:catn [::body :map]] :boolean]}
  [body]
  (let [message (-> body :choices first :message)]
    (and (str/blank? (or (:content message) ""))
         (not (str/blank? (or (:reasoning_content message) ""))))))

(defn reasoning-token-estimate
  "Estimated private-reasoning tokens in an assembled completion."
  {:malli/schema [:=> [:catn [::body :map]] :int]}
  [body]
  (tokens/estimate (or (-> body :choices first :message :reasoning_content)
                       "")))

(defn- valid-identity?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- response-identity-result
  [resolution label value]
  (when (some? value)
    (let [cap (get-in resolution
                      [:seon.ai/resolved-config
                       :seon.config.model-transport/response-identity-cap])]
      (cond
        (nil? cap) nil
        (and (valid-identity? value)
             (<= (count value) cap))
        {::identity-value value}

        :else
        {::identity-error
         (subs (str "Provider response " label
                    " exceeds its evidence bound.")
               0
               (min cap
                    (count (str "Provider response " label
                                " exceeds its evidence bound."))))}))))

(defn parse-completion
  "Interpret one already-data OpenAI ChatCompletion body."
  {:malli/schema
   [:=> [:catn [::body :map] [::resolution :map]] :map]}
  [body resolution]
  (let [choice (-> body :choices first)
        message (:message choice)
        content (:content message)
        tool-calls (:tool_calls message)
        finish-reason (:finish_reason choice)
        truncated? (= "length" finish-reason)
        model-result
        (response-identity-result resolution "model identity" (:model body))
        fingerprint-result
        (response-identity-result resolution
                                  "system fingerprint"
                                  (:system_fingerprint body))
        request-id-result
        (response-identity-result resolution "request identity" (:id body))
        response-model (::identity-value model-result)
        fingerprint (::identity-value fingerprint-result)
        request-id (::identity-value request-id-result)
        evidence-error
        (some ::identity-error
              [model-result fingerprint-result request-id-result])
        extras (apply dissoc body known-completion-keys)]
    (cond->
     {:seon.ai/text (or content "")
      :seon.ai.openai-compat/finish-reason finish-reason}
      truncated?
      (assoc :seon.ai/truncated? true)

      (and truncated? (str/blank? (or content "")))
      (assoc :seon.ai/error
             {:seon.ai/msg
              "Provider exhausted the configured completion limit before returning visible text."})

      (some? (:usage body))
      (assoc :seon.ai/usage (:usage body))

      (seq tool-calls)
      (assoc :seon.ai/tool-calls tool-calls)

      response-model
      (assoc :seon.ai/response-model response-model)

      fingerprint
      (assoc :seon.ai/system-fingerprint fingerprint)

      request-id
      (assoc :seon.ai/request-id request-id)

      evidence-error
      (assoc :seon.ai/evidence-error evidence-error)

      (seq extras)
      (assoc :seon.ai/provider-fields extras))))

(defn estimated-usage
  "Estimate prompt and completion usage for an aborted stream."
  {:malli/schema
   [:=> [:catn [::request :map] [::text :string]] :map]}
  [request text]
  (let [prompt-tokens
        (+ (tokens/estimate (:seon.ai/system-prompt request))
           (tokens/estimate (str (:seon.ai/ctx request))))
        completion-tokens (tokens/estimate (str text))]
    {:prompt_tokens prompt-tokens
     :completion_tokens completion-tokens
     :total_tokens (+ prompt-tokens completion-tokens)}))
