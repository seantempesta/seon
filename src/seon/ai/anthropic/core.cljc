(ns seon.ai.anthropic.core
  "Pure Anthropic Messages request and response transforms."
  (:require
   [clojure.string :as str]
   [seon.ai.core :as ai]
   [seon.ai.tokens :as tokens]))

(def ^:private known-message-keys
  #{:content :usage :id :type :role :model :stop_reason :stop_sequence
    :parsed_output})

(defn request-params
  "Build Anthropic Messages wire data from one frozen resolution."
  {:malli/schema
   [:=> [:catn [::request :map]
                 [::resolution :map]
                 [::context-parts :map]]
    :map]}
  [{:seon.ai/keys [ctx model max-tokens tools tool-choice] :as request}
   resolution
   {:seon.render/keys [stable-text volatile-text]}]
  (let [config (:seon.ai/resolved-config resolution)
        split? (not (or (str/blank? stable-text)
                        (str/blank? volatile-text)))
        tools* (or tools (:seon.ai/tools config))
        choice* (or tool-choice (:seon.ai/tool-choice config))]
    (cond->
     {:model (or model (:seon.ai/model config))
      :max_tokens (or max-tokens (:seon.ai/max-tokens config))
      :system
      (cond-> [{:type "text"
                :text (:seon.ai/system-prompt request)
                :cache_control {:type "ephemeral"}}]
        split?
        (conj {:type "text"
               :text stable-text
               :cache_control {:type "ephemeral"}}))
      :messages [{:role "user"
                  :content (if split? volatile-text ctx)}]}
      (ai/thinking-mode config)
      (assoc :thinking {:type "adaptive"})

      (some? tools*)
      (assoc :tools tools*)

      (some? choice*)
      (assoc :tool_choice choice*))))

(defn- text-of-blocks
  [content]
  (->> content
       (filter #(= "text" (:type %)))
       (map :text)
       (apply str)))

(defn- tool-use-blocks
  [content]
  (filterv #(= "tool_use" (:type %)) content))

(defn parse-completion
  "Interpret one already-data Anthropic Message body."
  {:malli/schema [:=> [:catn [::body :map]] :map]}
  [body]
  (let [stop-reason (:stop_reason body)
        tool-calls (tool-use-blocks (:content body))
        extras (apply dissoc body known-message-keys)]
    (if (= "refusal" stop-reason)
      {:seon.ai/text ""
       :seon.ai.anthropic/stop-reason stop-reason
       :seon.ai/usage (:usage body)
       :seon.ai/error
       {:seon.ai/msg
        (str "Anthropic refusal — the model declined this request "
             "(stop_reason \"refusal\", empty content). Rephrase or reduce "
             "the request; the call is not billed pre-output.")}}
      (cond->
       {:seon.ai/text (text-of-blocks (:content body))
        :seon.ai/usage (:usage body)}
        stop-reason
        (assoc :seon.ai.anthropic/stop-reason stop-reason)

        (seq tool-calls)
        (assoc :seon.ai/tool-calls tool-calls)

        (seq extras)
        (assoc :seon.ai/provider-fields extras)))))

(defn- stream-step
  "Fold one Anthropic SSE event into ordinary message data."
  [state event]
  (let [delta (:delta event)
        text (:text delta)
        input (get-in event [:message :usage :input_tokens])
        output (get-in event [:usage :output_tokens])]
    (cond-> state
      (string? text) (update :seon.ai.http/text str text)
      (int? input) (assoc-in [:seon.ai.http/usage :input_tokens] input)
      (int? output) (assoc-in [:seon.ai.http/usage :output_tokens] output)
      (:stop_reason delta)
      (assoc :seon.ai.http/stop-reason (:stop_reason delta)))))

(defn- estimated-usage
  [request text]
  (let [input (+ (tokens/estimate (:seon.ai/system-prompt request))
                 (tokens/estimate (str (:seon.ai/ctx request))))
        output (tokens/estimate text)]
    {:input_tokens input
     :output_tokens output}))

(defn complete
  "Execute one Anthropic request through a native HTTP leaf."
  {:malli/schema
   [:=> [:catn [::request :map] [::native-request 'fn?]] :map]}
  [request native-request!]
  (let [resolution (:seon.ai/config-resolution request)
        config (:seon.ai/resolved-config resolution)
        stream? (boolean (:seon.ai/stream? request))
        reply-evaluation (:seon.ai/reply-evaluation request)
        result
        (native-request!
         {:seon.ai.http/endpoint
          (str (str/replace (:seon.ai/base-url config) #"/+$" "")
               "/messages")
          :seon.ai.http/credential-candidates
          (ai/credential-candidates resolution)
          :seon.ai.http/config-resolution resolution
          :seon.ai.http/credential-header "x-api-key"
          :seon.ai.http/credential-prefix ""
          :seon.ai.http/headers {"Content-Type" "application/json"
                                 "anthropic-version" "2023-06-01"
                                 "Accept" (if stream?
                                            "text/event-stream"
                                            "application/json")}
          :seon.ai.http/body
          (assoc
           (request-params
            request resolution
            {:seon.render/stable-text ""
             :seon.render/volatile-text ""})
           :stream stream?)
          :seon.ai.http/request-timeout-ms
          (:seon.ai/request-timeout-ms request)
          :seon.ai.http/connect-timeout-ms
          (:seon.config.model-transport/connect-timeout-ms config)
          :seon.ai.http/maximum-response-bytes
          (:seon.config.model-transport/maximum-response-bytes config)
          :seon.ai.http/stream? stream?
          :seon.ai.http/stream-initial {:seon.ai.http/text ""}
          :seon.ai.http/stream-step stream-step
          :seon.ai.http/progress! (:seon.ai/progress! request)
          :seon.ai.http/stream-abort?
          #(and (= :first-form reply-evaluation)
                (ai/first-form-complete? (:seon.ai.http/text %)))})]
    (if (:seon.ai/error result)
      result
      (if stream?
        (let [text (:seon.ai.http/text result)
              response
              (merge
               (parse-completion
                {:content [{:type "text" :text text}]
                 :usage (:seon.ai.http/usage result)
                 :stop_reason (:seon.ai.http/stop-reason result)})
               (select-keys result [:seon.ai/config-evidence
                                    :seon.ai/status
                                    :seon.ai/provider-duration-ns]))]
          (cond-> response
            (or (:seon.ai.http/aborted? result)
                (nil? (:seon.ai.http/usage result)))
            (assoc :seon.ai/usage (estimated-usage request text)
                   :seon.ai/estimated? true)))
        (merge
         (parse-completion (:seon.ai.http/body result))
         (select-keys result [:seon.ai/config-evidence
                              :seon.ai/status
                              :seon.ai/provider-duration-ns]))))))
