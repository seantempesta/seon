(ns seon.ai.portable-test
  "Dual-tier proof for the portable LLM adapter core."
  (:require
   #?(:clj [clojure.edn :as reader]
      :cljs [cljs.reader :as reader])
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [seon.ai.anthropic.core :as anthropic]
   [seon.ai.core :as ai]
   [seon.ai.openai-compat.core :as openai]
   [seon.schema :as schema]))

(def ^:private attempt-timeout-ms 900000)

(def ^:private shipped-defaults
  {:openai-compat {:seon.ai/model "portable-model"
                   :seon.ai/completion-limit-field :max-completion-tokens}
   :anthropic {:seon.ai/model "claude-opus-4-8"}})

(def ^:private resolution
  (ai/resolved-config-from-rows
   shipped-defaults
   {:seon.ai/provider :openai-compat
    :seon.ai/model "portable-model"
    :seon.ai/max-tokens 2048
    :seon.ai/completion-limit-field :max-completion-tokens
    :seon.ai/base-url "https://provider.example/v1"
    :seon.ai/extra-body-edn "{:vendor-option true}"
    :seon.config.model-transport/response-identity-cap 128}
   {:seon.ai/agent-temperature 0.25}
   attempt-timeout-ms))

(def ^:private openai-response-bytes
  (str "{:id \"request-1\" :model \"portable-model\" "
       ":system_fingerprint \"fingerprint-1\" "
       ":choices [{:message {:content \"(+ 20 22)\" "
       ":reasoning_content \"private\"} :finish_reason \"stop\"}] "
       ":usage {:prompt_tokens 3 :completion_tokens 4 :total_tokens 7} "
       ":vendor_field {:score 1}}"))

(def ^:private anthropic-response-bytes
  (str "{:id \"message-1\" :type \"message\" :role \"assistant\" "
       ":model \"claude-opus-4-8\" :stop_reason \"tool_use\" "
       ":content [{:type \"thinking\" :thinking \"private\"} "
       "{:type \"text\" :text \"calling\"} "
       "{:type \"tool_use\" :id \"tool-1\" :name \"f\" :input {:x 1}}] "
       ":usage {:input_tokens 3 :output_tokens 2} "
       ":container {:id \"container-1\"}}"))

(defn- fake-openai-leaf
  [request response-bytes]
  {:request (openai/request-params request resolution)
   :response (openai/parse-completion
              (reader/read-string response-bytes)
              resolution)})

(defn- fake-anthropic-leaf
  [request response-bytes]
  {:request
   (anthropic/request-params
    request
    (ai/resolved-config-from-rows
     shipped-defaults
     {:seon.ai/provider :anthropic
      :seon.ai/model "claude-opus-4-8"
      :seon.ai/max-tokens 1024}
     {}
     attempt-timeout-ms)
    {:seon.render/stable-text "stable"
     :seon.render/volatile-text "volatile"})
   :response
   (anthropic/parse-completion (reader/read-string response-bytes))})

(deftest same-openai-wire-data-builds-and-interprets-on-both-tiers
  (let [result
        (fake-openai-leaf
         {:seon.ai/ctx "Return one form."
          :seon.ai/system-prompt "Portable system."}
         openai-response-bytes)]
    (testing "request builder preserves the provider wire vocabulary"
      (is (= {:model "portable-model"
              :messages [{:role "system" :content "Portable system."}
                         {:role "user" :content "Return one form."}]
              :max_completion_tokens 2048
              :temperature 0.25}
             (:request result))))
    (testing "already-data response interpretation is byte-fixture stable"
      (is (= {:seon.ai/text "(+ 20 22)"
              :seon.ai.openai-compat/finish-reason "stop"
              :seon.ai/usage
              {:prompt_tokens 3 :completion_tokens 4 :total_tokens 7}
              :seon.ai/response-model "portable-model"
              :seon.ai/system-fingerprint "fingerprint-1"
              :seon.ai/request-id "request-1"
              :seon.ai/provider-fields {:vendor_field {:score 1}}}
             (:response result))))))

(deftest same-anthropic-wire-data-builds-and-interprets-on-both-tiers
  (let [result
        (fake-anthropic-leaf
         {:seon.ai/ctx "whole"
          :seon.ai/system-prompt "Portable system."}
         anthropic-response-bytes)]
    (is (= {:model "claude-opus-4-8"
            :max_tokens 1024
            :system [{:type "text"
                      :text "Portable system."
                      :cache_control {:type "ephemeral"}}
                     {:type "text"
                      :text "stable"
                      :cache_control {:type "ephemeral"}}]
            :messages [{:role "user" :content "volatile"}]}
           (:request result)))
    (is (= {:seon.ai/text "calling"
            :seon.ai/usage {:input_tokens 3 :output_tokens 2}
            :seon.ai.anthropic/stop-reason "tool_use"
            :seon.ai/tool-calls
            [{:type "tool_use" :id "tool-1" :name "f" :input {:x 1}}]
            :seon.ai/provider-fields {:container {:id "container-1"}}}
           (:response result)))))

(deftest shared-resolution-and-failure-vocabulary-are-portable
  (is (schema/registered? :seon.ai/error))
  (is (schema/valid-candidate-value?
       :seon.ai/error
       {:seon.ai/msg "provider unavailable"
        :seon.ai/status 503
        :seon.ai/retry-after-ms 2000}))
  (is (= attempt-timeout-ms
         (:seon.ai/agent-attempt-timeout-ms resolution)))
  (is (= {:vendor-option true} (:seon.ai/extra-body resolution)))
  (is (= 2000 (ai/parse-retry-after-ms "2" 0)))
  (is (nil? (ai/parse-retry-after-ms "not-a-delay" 0))))
