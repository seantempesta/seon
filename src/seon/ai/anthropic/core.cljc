(ns seon.ai.anthropic.core
  "Pure Anthropic Messages request and response transforms."
  (:require
   [clojure.string :as str]
   [seon.ai.core :as ai]))

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
