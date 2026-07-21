(ns seon.ai.dispatch-test
  "Behavioral coverage for authority-resolved provider dispatch."
  (:require
    [cljs.test :refer [async deftest is]]
    [seon.ai.anthropic :as anthropic]
    [seon.ai.dispatch :as dispatch]
    [seon.ai.openai-compat :as openai]
    [seon.diffusion.gemma :as diffusiongemma]))

(defn- resolution
  ([provider] (resolution provider nil))
  ([provider backend]
   {:seon.ai/resolved-config
    (cond-> {:seon.ai/provider provider}
      backend (assoc :seon.ai/dg-backend backend))
    :seon.ai/provenance {:seon.ai/provider :default}}))

(defn- tagged-adapter [tag] (fn [_] {:selected tag}))
(defn- descriptor [configured? adapter]
  {::dispatch/configured? configured?
   ::dispatch/agent-adapter (fn [] adapter)})

(deftest authority-resolution-selects-the-wire-adapter
  (let [anthropic-adapter (tagged-adapter :anthropic)
        openai-adapter (tagged-adapter :openai)
        control-adapter (tagged-adapter :control)
        typeahead-adapter (tagged-adapter :typeahead)
        originals (dispatch/registered-providers)]
    (try
      (dispatch/register-providers!
       {:anthropic (descriptor (constantly true) anthropic-adapter)
        :deepseek (descriptor (constantly true) openai-adapter)
        :openai-compat (descriptor (constantly true) openai-adapter)
        :diffusiongemma (descriptor (constantly true) control-adapter)
        :typeahead (descriptor (constantly true) typeahead-adapter)})
      (doseq [[provider backend expected]
              [[:anthropic nil anthropic-adapter]
               [:deepseek nil openai-adapter]
               [:openai-compat nil openai-adapter]
               [:diffusiongemma :control control-adapter]
               [:typeahead nil typeahead-adapter]]]
        (is (identical? expected (dispatch/adapter (resolution provider backend)))))
      (finally (dispatch/register-providers! originals)))))

(deftest absent-provider-credentials-select-the-stub
  (let [unexpected (fn [& _]
                     (throw (js/Error. "adapter constructed without credentials")))
        originals (dispatch/registered-providers)]
    (try
      (dispatch/register-providers!
       (into {}
             (map (fn [provider]
                    [provider (descriptor (constantly false) unexpected)]))
             (keys originals)))
      (doseq [[provider backend]
              [[:anthropic nil] [:deepseek nil] [:openai-compat nil]
               [:diffusiongemma :control] [:diffusiongemma :vllm]
               [:typeahead nil]]]
        (is (identical? dispatch/stub
                        (dispatch/adapter (resolution provider backend)))))
      (finally (dispatch/register-providers! originals)))))

(deftest diffusion-provider-descriptor-owns-backend-selection
  (let [control-adapter (tagged-adapter :control)
        openai-adapter (tagged-adapter :openai)
        constructor (fn [adapter] (fn ([] adapter) ([_] adapter)))]
    (with-redefs [diffusiongemma/api-configured? (constantly true)
                  openai/api-key-configured? (constantly true)
                  diffusiongemma/agent-adapter (constructor control-adapter)
                  openai/agent-adapter (constructor openai-adapter)]
      (doseq [[backend expected]
              [[:control :control] [:vllm :openai]]]
        (let [resolved (resolution :diffusiongemma backend)
              selected ((dispatch/adapter resolved)
                        {:seon.ai/ctx "ctx"
                         :seon.ai/config-resolution resolved})]
          (is (= expected (:selected selected))))))))

(deftest registration-rejects-provider-ids-outside-the-locality-authority
  (let [before (dispatch/registered-providers)
        result (dispatch/register-providers!
                {:unknown-provider (descriptor (constantly true)
                                               (tagged-adapter :unknown))})]
    (is (= :user-input (get-in result [:seon/error :seon.error/kind])))
    (is (re-find #"declare provider locality"
                 (get-in result [:seon/error :seon.error/message])))
    (is (= before (dispatch/registered-providers)))))

(deftest unregistered-selection-steers-through-the-existing-stub-reply
  (async done
    (let [registered (dispatch/registered-providers)]
      (with-redefs [dispatch/registered-providers
                    (constantly (dissoc registered :diffusiongemma))]
        (-> (dispatch/stub {:seon.ai/ctx "ctx"
                            :seon.ai/config-resolution
                            (resolution :diffusiongemma)})
            (.then (fn [response]
                     (is (re-find #":diffusiongemma is not registered"
                                  (:text response)))
                     (done)))
            (.catch (fn [error]
                      (is false (str error))
                      (done))))))))

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

(deftest invalid-direct-input-is-an-error-value
  (async done
    (let [llm (dispatch/llm-fn)]
      (-> (js/Promise.all
            (clj->js
              (map llm
                   ["bare context"
                    {:seon.ai/ctx "missing resolution"}
                    {:seon.ai/ctx "extra field"
                     :seon.ai/config-resolution (resolution :deepseek)
                     :seon.ai/legacy true}])))
          (.then (fn [responses]
                   (doseq [response (array-seq responses)]
                     (is (= "" (:text response)))
                     (is (re-find #":seon.ai/request"
                                  (get-in response [:seon.ai/error
                                                    :seon.ai/msg]))))))
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
                 {:seon.diffusion.gemma/mode :generate
                  :seon.diffusion.gemma/prompt "ctx"})])
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
