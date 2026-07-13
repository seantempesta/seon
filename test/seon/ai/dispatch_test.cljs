(ns seon.ai.dispatch-test
  "Behavioral coverage for effective-provider dispatch."
  (:require
    [cljs.test :refer [async deftest is]]
    [clojure.string :as str]
    [seon.ai :as ai]
    [seon.ai.anthropic :as anthropic]
    [seon.ai.diffusiongemma :as diffusiongemma]
    [seon.ai.openai-compat :as openai]
    [seon.ai.dispatch :as dispatch]
    [seon.ai.tokens :as tokens]
    [seon.ai.typeahead :as typeahead]
    [seon.config :as config]))

(defn- tagged-adapter
  "An adapter function tagged by its constructor's identity."
  [tag]
  (fn [_arg] {::selected tag}))

(defn- adapter-constructor
  "A mock matching each production adapter constructor's two arities."
  [adapter]
  (fn
    ([] adapter)
    ([_opts] adapter)))

(deftest effective-provider-and-backend-select-their-wire-adapter
  (let [!provider (atom :deepseek)
        !backend  (atom :control)
        anthropic-adapter (tagged-adapter :anthropic)
        openai-adapter    (tagged-adapter :openai-compatible)
        control-adapter   (tagged-adapter :diffusion-control)
        typeahead-adapter (tagged-adapter :typeahead)]
    (with-redefs [ai/provider                         (fn [] @!provider)
                  ai/dg-backend                       (fn [] @!backend)
                  config/anthropic-api-key            (fn [] "configured")
                  openai/api-key-configured?          (fn [] true)
                  diffusiongemma/api-configured?      (fn [] true)
                  anthropic/agent-adapter              (adapter-constructor anthropic-adapter)
                  openai/agent-adapter                 (adapter-constructor openai-adapter)
                  diffusiongemma/agent-adapter         (adapter-constructor control-adapter)
                  typeahead/agent-adapter              (adapter-constructor typeahead-adapter)]
      (doseq [[effective-provider backend expected]
              [[:anthropic nil anthropic-adapter]
               [:deepseek nil openai-adapter]
               [:openai-compat nil openai-adapter]
               [:diffusiongemma :control control-adapter]
               [:diffusiongemma :vllm openai-adapter]
               [:typeahead nil typeahead-adapter]]]
        (reset! !provider effective-provider)
        (when backend (reset! !backend backend))
        (is (identical? expected (dispatch/adapter))
            (str effective-provider
                 (when backend (str "/" backend))
                 " selects its wire adapter"))))))

(deftest absent-provider-credentials-select-the-stub
  (let [!provider (atom :deepseek)
        !backend  (atom :control)
        unexpected (fn
                     ([] (throw (js/Error. "adapter constructed without credentials")))
                     ([_opts] (throw (js/Error. "adapter constructed without credentials"))))]
    (with-redefs [ai/provider                    (fn [] @!provider)
                  ai/dg-backend                  (fn [] @!backend)
                  config/anthropic-api-key       (fn [] nil)
                  openai/api-key-configured?     (fn [] false)
                  diffusiongemma/api-configured? (fn [] false)
                  anthropic/agent-adapter         unexpected
                  openai/agent-adapter            unexpected
                  diffusiongemma/agent-adapter    unexpected
                  typeahead/agent-adapter         unexpected]
      (doseq [[effective-provider backend]
              [[:anthropic nil]
               [:deepseek nil]
               [:openai-compat nil]
               [:diffusiongemma :control]
               [:diffusiongemma :vllm]
               [:typeahead nil]]]
        (reset! !provider effective-provider)
        (when backend (reset! !backend backend))
        (is (identical? dispatch/stub (dispatch/adapter))
            (str effective-provider
                 (when backend (str "/" backend))
                 " falls back without constructing an adapter"))))))

(deftest dispatching-llm-selects-again-for-every-call
  (let [!selected (atom :first)
        !selections (atom [])]
    (with-redefs [dispatch/adapter
                  (fn []
                    (let [selected @!selected]
                      (swap! !selections conj selected)
                      (tagged-adapter selected)))]
      (let [llm (dispatch/llm-fn)]
        (is (= :first (::selected (llm "ctx"))))
        (reset! !selected :second)
        (is (= :second (::selected (llm "ctx"))))
        (is (= [:first :second] @!selections)
            "the closure retains no boot-time adapter")))))

(deftest stub-keeps-the-buffered-and-streaming-call-shapes-equivalent
  (async done
    (let [ctx "eightchr"
          buffered (dispatch/stub ctx)
          streaming (dispatch/stub {:seon.ai/ctx ctx
                                    :seon.ai/stream? true})]
      (is (instance? js/Promise buffered))
      (-> (js/Promise.all #js [buffered streaming])
          (.then
            (fn [results]
              (let [[buffered-response streaming-response] (array-seq results)
                    text (:text buffered-response)]
                (is (= buffered-response streaming-response)
                    "the stub ignores streaming while preserving the ctx")
                (is (str/includes? text "(message/user")
                    "the reply remains an actionable agent form")
                (is (str/includes? text
                                   (str (tokens/estimate ctx) " tokens of ctx"))
                    "the reply reports the canonical token estimate"))))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "threw — " error))
                    (done)))))))
