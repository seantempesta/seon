(ns seon.ai.provider-test
  "Portable tests for hosted provider descriptor data."
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [seon.ai.core :as ai]
   [seon.ai.openai-compat.core :as openai]
   [seon.ai.provider :as provider]
   [seon.schema :as schema]))

(def ^:private expected-hosted-providers
  #{:deepseek :openai-compat :kimi :zai :openrouter :anthropic :gemini})

(deftest hosted-descriptors-are-valid-identified-data
  (is (= expected-hosted-providers
         (set (keys provider/hosted-provider-descriptors))))
  (doseq [[provider-id descriptor]
          provider/hosted-provider-descriptors]
    (testing (name provider-id)
      (is (= provider-id (:seon.ai.provider/id descriptor)))
      (is (= :frontier (:seon.ai.provider/locality descriptor)))
      (is (schema/valid-candidate-value?
           :seon.ai.provider/descriptor
           descriptor))))
  (testing "compiled pod-only local workers never become hosted rows"
    (is (= :local-worker
           (get provider/provider-locality :diffusiongemma)))
    (is (= :local-worker
           (get provider/provider-locality :typeahead)))
    (is (not (contains? provider/hosted-provider-descriptors
                        :diffusiongemma)))
    (is (not (contains? provider/hosted-provider-descriptors
                        :typeahead)))))

(deftest provider-rows-carry-the-qualified-wire-differences
  (is (= :max-completion-tokens
         (get-in provider/hosted-provider-descriptors
                 [:kimi :seon.ai.provider/completion-limit-field])))
  (is (= #{:auto}
         (get-in provider/hosted-provider-descriptors
                 [:zai :seon.ai.provider/allowed-tool-choices])))
  (is (= :none
         (get-in provider/hosted-provider-descriptors
                 [:openrouter :seon.ai.provider/stream-options-policy])))
  (is (= :anthropic
         (get-in provider/hosted-provider-descriptors
                 [:anthropic :seon.ai.provider/adapter-core])))
  (is (= "https://generativelanguage.googleapis.com/v1beta/openai"
         (get-in provider/hosted-provider-descriptors
                 [:gemini :seon.ai.provider/default-base-url])))
  (is (true?
       (get-in provider/hosted-provider-descriptors
               [:gemini :seon.ai.provider/streaming-actually-works?]))))

(deftest fictitious-hosted-row-builds-through-the-fixed-openai-core
  (let [descriptor
        {:seon.ai.provider/id :fictitious
         :seon.ai.provider/locality :frontier
         :seon.ai.provider/adapter-core :openai-compat
         :seon.ai.provider/default-base-url "https://fiction.example/v9"
         :seon.ai.provider/endpoint-policy :openai-chat-completions
         :seon.ai.provider/credential-header "Authorization"
         :seon.ai.provider/credential-prefix "Bearer "
         :seon.ai.provider/default-api-key-env "FICTION_API_KEY"
         :seon.ai.provider/retry-after-header "retry-after"
         :seon.ai.provider/retry-after-format :delta-seconds-or-http-date
         :seon.ai.provider/default-model "fiction-1"
         :seon.ai.provider/default-max-tokens 123
         :seon.ai.provider/completion-limit-field :max-completion-tokens
         :seon.ai.provider/thinking-policy :omit
         :seon.ai.provider/stream-options-policy :openai-include-usage
         :seon.ai.provider/streaming-advertised? true
         :seon.ai.provider/streaming-actually-works? false
         :seon.ai.provider/usage-in-stream? true
         :seon.ai.provider/function-calling? true
         :seon.ai.provider/response-format? true
         :seon.ai.provider/allowed-tool-choices #{:auto}
         :seon.ai.provider.usage/input-field :prompt_tokens
         :seon.ai.provider.usage/output-field :completion_tokens
         :seon.ai.provider.usage/total-field :total_tokens}
        resolution
        (ai/resolved-config-from-rows
         {}
         {:seon.config/provider-descriptors [descriptor]}
         {:seon.ai/agent-provider :fictitious}
         60000)]
    (is (schema/valid-candidate-value?
         :seon.ai.provider/descriptor
         descriptor))
    (is (= :fictitious
           (get-in resolution
                   [:seon.ai/resolved-config :seon.ai/provider])))
    (is (= :openai-compat
           (ai/resolved-adapter (:seon.ai/resolved-config resolution))))
    (is (= {:model "fiction-1"
            :messages [{:role "system" :content "System."}
                       {:role "user" :content "Return one form."}]
            :max_completion_tokens 123}
           (openai/request-params
            {:seon.ai/ctx "Return one form."
             :seon.ai/system-prompt "System."}
            resolution)))))
