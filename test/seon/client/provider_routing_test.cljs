(ns seon.client.provider-routing-test
  "Provider dispatch consumes one authority-resolved request value."
  (:require
    [cljs.test :refer [async deftest is]]
    [seon.ai :as ai]
    [seon.ai.dispatch :as dispatch]))

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

(defn- descriptor [provider]
  {::dispatch/configured? (constantly true)
   ::dispatch/agent-adapter (tagging-adapter provider)})

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
    (let [originals (dispatch/registered-providers)
          _ (dispatch/register-providers!
             {:anthropic (descriptor :anthropic)
              :deepseek (descriptor :deepseek)})
          llm (dispatch/llm-fn)]
      (-> (js/Promise.all
            #js [(llm (request (resolution :deepseek :anthropic)))
                 (llm (request (resolution :deepseek nil)))])
            (.then
              (fn [responses]
                (let [[override inherited] (array-seq responses)]
                  (is (= :anthropic (::provider override)))
                  (is (= :deepseek (::provider inherited))))))
            (.finally (fn [] (dispatch/register-providers! originals)))
            (.then (fn [_] (done)))
            (.catch (fn [error]
                      (is false (str "threw — " error))
                      (done)))))))

(deftest explicit-inherit-uses-the-global-provider
  (async done
    (let [originals (dispatch/registered-providers)
          _ (dispatch/register-providers!
             {:anthropic (descriptor :anthropic)})]
      (-> ((dispatch/llm-fn)
            (request (resolution :anthropic :inherit)))
          (.then (fn [response]
                   (is (= :anthropic (::provider response)))))
          (.finally (fn [] (dispatch/register-providers! originals)))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "threw — " error))
                    (done)))))))
