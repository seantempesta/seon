(ns seon.agent.prompt-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [seon.agent.ctx :as ctx]
   [seon.agent.prompt :as prompt]
   [seon.schema :as schema]))

(def database
  {:db-name "prompt-test"
   :store-id [#uuid "10000000-0000-0000-0000-000000000001" :db]
   :t 17
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id
   #uuid "5a33f7e8-e93a-4a3c-94b4-7ad8365e686e"})

(def configuration
  {:seon.config/system-text "; frozen system"
   :seon.ai/provider :deepseek
   :seon.ai/model "deepseek-chat"
   :seon.ai/wire-stream? true
   :seon.ai/reply-evaluation :first-form
   :seon.config.model-stream/partial-publish-settle-ms 25
   :seon.config.llm-retry/base-wait-ms 10
   :seon.config.llm-retry/growth-factor 2.0
   :seon.config.llm-retry/jitter-fraction 0.0
   :seon.config.llm-retry/maximum-wait-ms 100
   :seon.config.llm-retry/maximum-total-wait-ms 500
   :seon.config.llm-retry/default-retries 2})

(def request
  {:seon.db/db database
   :seon.agent/entity
   {:seon.agent/id "agent-1"
    :seon.ai/agent-temperature 0.2}
   :seon.config/configuration configuration
   :seon.agent.prompt/attempt-timeout-ms 1234
   :seon.agent.ctx/selected-blocks
   [{:seon.agent.ctx/name :stable
     :seon.agent.ctx/priority 10
     :seon.render/ai "stable"}
    {:seon.agent.ctx/name :volatile
     :seon.agent.ctx/priority 30
     :seon.render/ai "volatile"}]})

(deftest composes-the-established-frozen-prompt-projections
  (let [rendered (prompt/render request)]
    (is (schema/valid-candidate-value?
         :seon.agent.prompt/render-request request))
    (is (schema/valid-candidate-value?
         :seon.agent.prompt/rendered-prompt rendered))
    (testing "the context formatter remains the one owner"
      (is (= (ctx/rendered-context-from-entity
              {:seon.agent/entity (:seon.agent/entity request)
               :seon.agent.ctx/selected-blocks
               (:seon.agent.ctx/selected-blocks request)})
             (select-keys
              rendered
              [:seon.render/text :seon.agent.ctx/rendered-blocks])))
      (is (str/includes? (:seon.render/text rendered)
                         ctx/stable-boundary)))
    (testing "the frozen database value and LLM projections travel together"
      (is (identical? database (:seon.db/db rendered)))
      (is (= 'my.agent.agent-1 (:seon.eval/ns rendered)))
      (is (= true (:seon.ai/wire-stream? rendered)))
      (is (= :first-form (:seon.ai/reply-evaluation rendered)))
      (is (= 25
             (:seon.config.model-stream/partial-publish-settle-ms
              rendered)))
      (is (= 10 (:seon.config.llm-retry/base-wait-ms rendered)))
      (is (= 1234
             (get-in rendered
                     [:seon.ai/config-resolution
                      :seon.ai/agent-attempt-timeout-ms])))
      (is (= 0.2
             (get-in rendered
                     [:seon.ai/config-resolution
                      :seon.ai/resolved-config
                      :seon.ai/temperature])))
      (is (str/starts-with? (:seon.ai/system-prompt rendered)
                            "; frozen system")))))

(deftest honors-the-acquired-namespace-and-whole-prompt
  (let [rendered
        (prompt/render
         (assoc request
                :seon.eval/ns 'my.photos
                :seon.agent.ctx/selected-blocks []
                :seon.agent.ctx/whole-prompt "one complete prompt"))]
    (is (= 'my.photos (:seon.eval/ns rendered)))
    (is (= "one complete prompt" (:seon.render/text rendered)))
    (is (= [:prompt]
           (mapv :seon.agent.ctx/name
                 (:seon.agent.ctx/rendered-blocks rendered))))))
