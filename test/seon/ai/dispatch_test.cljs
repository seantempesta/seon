(ns seon.ai.dispatch-test
  "Behavioral coverage for authority-resolved provider dispatch."
  (:require
    [cljs.test :refer [async deftest is]]
    [seon.ai.anthropic :as anthropic]
    [seon.ai.diffusiongemma :as diffusiongemma]
    [seon.ai.dispatch :as dispatch]
    [seon.ai.openai-compat :as openai]
    [seon.ai.typeahead :as typeahead]))

(defn- resolution
  ([provider] (resolution provider nil))
  ([provider backend]
   {:seon.ai/resolved-config
    (cond-> {:seon.ai/provider provider}
      backend (assoc :seon.ai/dg-backend backend))
    :seon.ai/provenance {:seon.ai/provider :default}}))

(defn- tagged-adapter [tag] (fn [_] {:selected tag}))
(defn- constructor [adapter] (fn ([] adapter) ([_] adapter)))

(deftest authority-resolution-selects-the-wire-adapter
  (let [anthropic-adapter (tagged-adapter :anthropic)
        openai-adapter (tagged-adapter :openai)
        control-adapter (tagged-adapter :control)
        typeahead-adapter (tagged-adapter :typeahead)]
    (with-redefs [anthropic/api-key-configured? (constantly true)
                  openai/api-key-configured? (constantly true)
                  diffusiongemma/api-configured? (constantly true)
                  anthropic/agent-adapter (constructor anthropic-adapter)
                  openai/agent-adapter (constructor openai-adapter)
                  diffusiongemma/agent-adapter (constructor control-adapter)
                  typeahead/agent-adapter (constructor typeahead-adapter)]
      (doseq [[provider backend expected]
              [[:anthropic nil anthropic-adapter]
               [:deepseek nil openai-adapter]
               [:openai-compat nil openai-adapter]
               [:diffusiongemma :control control-adapter]
               [:diffusiongemma :vllm openai-adapter]
               [:typeahead nil typeahead-adapter]]]
        (is (identical? expected (dispatch/adapter (resolution provider backend))))))))

(deftest absent-provider-credentials-select-the-stub
  (let [unexpected (fn [& _]
                     (throw (js/Error. "adapter constructed without credentials")))]
    (with-redefs [anthropic/api-key-configured? (constantly false)
                  openai/api-key-configured? (constantly false)
                  diffusiongemma/api-configured? (constantly false)
                  anthropic/agent-adapter unexpected
                  openai/agent-adapter unexpected
                  diffusiongemma/agent-adapter unexpected
                  typeahead/agent-adapter unexpected]
      (doseq [[provider backend]
              [[:anthropic nil] [:deepseek nil] [:openai-compat nil]
               [:diffusiongemma :control] [:diffusiongemma :vllm]
               [:typeahead nil]]]
        (is (identical? dispatch/stub
                        (dispatch/adapter (resolution provider backend))))))))

(deftest dispatch-consumes-the-supplied-resolution-on-every-call
  (let [seen (atom [])
        llm (dispatch/llm-fn)]
    (with-redefs [dispatch/adapter
                  (fn [resolved]
                    (swap! seen conj resolved)
                    (tagged-adapter (get-in resolved
                                            [:seon.ai/resolved-config
                                             :seon.ai/provider])))]
      (is (= :deepseek
             (:selected (llm {:seon.ai/ctx "one"
                              :seon.ai/config-resolution
                              (resolution :deepseek)}))))
      (is (= :anthropic
             (:selected (llm {:seon.ai/ctx "two"
                              :seon.ai/config-resolution
                              (resolution :anthropic)}))))
      (is (= [:deepseek :anthropic]
             (mapv #(get-in % [:seon.ai/resolved-config :seon.ai/provider])
                   @seen))))))

(deftest missing-resolution-is-an-error-value
  (async done
    (let [llm (dispatch/llm-fn)]
      (-> (llm "legacy bare context")
          (.then (fn [response]
                   (is (= "" (:text response)))
                   (is (re-find #"config-resolution"
                                (get-in response [:seon.ai/error
                                                  :seon.ai/msg])))))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "rejected — " error))
                    (done)))))))

(deftest providers-reject-missing-resolution-as-data
  (async done
    (-> (js/Promise.all
          #js [(openai/complete {:seon.ai/ctx "ctx"})
               (anthropic/complete {:seon.ai/ctx "ctx"})
               (diffusiongemma/complete
                 {:seon.ai.diffusiongemma/mode :generate
                  :seon.ai.diffusiongemma/prompt "ctx"})])
        (.then (fn [responses]
                 (doseq [response (array-seq responses)]
                   (is (= "" (:seon.ai/text response)))
                   (is (re-find #"config-resolution"
                                (get-in response [:seon.ai/error
                                                  :seon.ai/msg]))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "rejected — " error))
                  (done))))))
