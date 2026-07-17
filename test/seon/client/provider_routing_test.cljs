(ns seon.client.provider-routing-test
  "Provider dispatch consumes one authority-resolved request value."
  (:require
    [cljs.test :refer [async deftest is]]
    [seon.ai :as ai]
    [seon.ai.anthropic :as anthropic]
    [seon.ai.dispatch :as dispatch]
    [seon.ai.openai-compat :as openai]))

(defn- tagging-adapter
  "A provider adapter that returns its selected provider."
  [provider]
  (let [make (fn []
               (fn [_request]
                 (js/Promise.resolve
                   {:text (str provider) ::provider provider})))]
    (fn
      ([] (make))
      ([_options] (make)))))

(defn- resolution
  "Resolve the same config-row and agent-row values used by a turn."
  [provider agent-provider]
  (ai/resolved-config-from-rows
    {::ai/provider provider}
    (cond-> {}
      agent-provider (assoc ::ai/agent-provider agent-provider))))

(defn- request
  [resolution]
  {::ai/ctx "ctx" ::ai/config-resolution resolution})

(deftest supplied-agent-resolution-selects-its-provider
  (async done
    (with-redefs [anthropic/api-key-configured? (constantly true)
                  openai/api-key-configured? (constantly true)
                  anthropic/agent-adapter (tagging-adapter :anthropic)
                  openai/agent-adapter (tagging-adapter :deepseek)]
      (let [llm (dispatch/llm-fn)]
        (-> (js/Promise.all
              #js [(llm (request (resolution :deepseek :anthropic)))
                   (llm (request (resolution :deepseek nil)))])
            (.then
              (fn [responses]
                (let [[override inherited] (array-seq responses)]
                  (is (= :anthropic (::provider override)))
                  (is (= :deepseek (::provider inherited))))))
            (.then (fn [_] (done)))
            (.catch (fn [error]
                      (is false (str "threw — " error))
                      (done))))))))

(deftest explicit-inherit-uses-the-global-provider
  (async done
    (with-redefs [anthropic/api-key-configured? (constantly true)
                  anthropic/agent-adapter (tagging-adapter :anthropic)]
      (-> ((dispatch/llm-fn)
           (request (resolution :anthropic :inherit)))
          (.then (fn [response]
                   (is (= :anthropic (::provider response)))))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "threw — " error))
                    (done)))))))
