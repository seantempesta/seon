(ns seon.agent-retry-test
  "Behavioral tests for the one bounded LLM retry path."
  (:require
   [clojure.string :as str]
   [cljs.test :refer [async deftest is testing]]
   [seon.agent.turn :as turn]
   [seon.db.branch :as db.branch]
   [seon.execution :as execution]
   [seon.execution.host :as execution.host]))

(def ^:private database-value
  {::db.branch/store-id #uuid "00000000-0000-4000-8000-000000000081"
   ::db.branch/name :db
   ::db.branch/commit-id #uuid "00000000-0000-4000-8000-000000000082"
   ::db.branch/basis-t 42})

(def ^:private base-resolution
  {:seon.ai/resolved-config
   {:seon.ai/provider :openai-compat
    :seon.ai/model "model-a"
    :seon.ai/temperature 0.0
    :seon.ai/completion-limit-field :max-completion-tokens
    :seon.ai/base-url "https://user:secret@a.example/v1?sig=hide#frag"
    :seon.ai/timeout-ms 1111
    :seon.config.model-transport/response-identity-cap 80
    :seon.config.model-transport/endpoint-cap 256}
   :seon.ai/provenance
   {:seon.ai/provider :config-row
    :seon.ai/model :config-row
    :seon.ai/temperature :config-row
    :seon.ai/base-url :config-row
    :seon.ai/timeout-ms :config-row}})

(defn- response-fn [calls responses]
  (fn [arg]
    (swap! calls conj arg)
    (js/Promise.resolve
     (nth responses (dec (count @calls)) (last responses)))))

(defn- transport-failure []
  {:text ""
   :seon.ai/error {:seon.ai/msg "DeepSeek fetch failed: fetch failed"
                   :seon.ai/transport? true}})

(defn- http-failure [status message]
  {:text ""
   :seon.ai/error {:seon.ai/msg message
                   :seon.ai/status status}})

(defn- invoke-turn!
  ([llm-fn] (invoke-turn! llm-fn base-resolution))
  ([llm-fn resolution]
   (turn/ask-and-eval!
    {:seon.agent/id "AGTretry00001"
     :seon.agent/llm-fn llm-fn
     ::db.branch/head database-value
     :seon.db/db database-value
     :seon.ai/config-resolution resolution
     :seon.agent.turn/id-of-turn "turn-retry-test"
     :seon.agent.turn/turn-idx 1
     :seon.agent.turn/prompt-text "ctx"})))

(defn- with-compiled-result [body]
  (let [original execution.host/invoke-compiled!]
    (set! execution.host/invoke-compiled!
          (fn [requested-database _agent-id _function-symbol _arguments]
            (js/Promise.resolve
             {::execution/message execution/result-message
              :seon.db/db requested-database
              ::execution/result {:seon.eval/n-ok 0
                                  :seon.eval/n-fail 0
                                  :seon.eval/ids []}})))
    (-> (js/Promise.resolve (body))
        (.finally #(set! execution.host/invoke-compiled! original)))))

(deftest transport-error-retries-to-the-cap-then-fails-honestly
  (async done
    (let [calls (atom [])
          resolution (assoc base-resolution :seon.ai/agent-max-retries 1)]
      (-> (invoke-turn! (response-fn calls [(transport-failure)]) resolution)
          (.then
           (fn [result]
             (testing "one retry is bounded by the resolved agent policy"
               (is (= 2 (count @calls)))
               (is (= :error (:seon.agent.turn/status result)))
               (is (= 0 (:seon.agent/eval-count result)))
               (is (= 1 (:seon.agent.turn/llm-retries result))))
             (testing "the final provider failure and both attempts remain data"
               (is (str/includes? (:seon.agent.turn/error result) "fetch failed"))
               (is (= [0 1]
                      (mapv :seon.ai.attempt/ordinal
                            (:seon.agent.turn/llm-attempts result))))
               (is (= [:provider-error :provider-error]
                      (mapv :seon.ai.attempt/outcome
                            (:seon.agent.turn/llm-attempts result)))))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest non-transient-http-error-does-not-retry
  (async done
    (let [calls (atom [])]
      (-> (invoke-turn!
           (response-fn calls [(http-failure 400 "bad request")]))
          (.then
           (fn [result]
             (is (= 1 (count @calls)))
             (is (= :error (:seon.agent.turn/status result)))
             (is (not (contains? result :seon.agent.turn/llm-retries)))
             (is (= 400
                    (-> result :seon.agent.turn/llm-attempts first
                        :seon.ai.attempt/error-status)))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest transient-error-then-success-keeps-one-resolution
  (async done
    (let [calls (atom [])
          resolution (assoc base-resolution :seon.ai/agent-max-retries 1)]
      (-> (with-compiled-result
            #(invoke-turn!
              (response-fn calls
                           [(transport-failure)
                            {:text ""
                             :seon.ai/raw
                             {:seon.ai/response-model "response-model"
                              :seon.ai/system-fingerprint "fp-1"
                              :seon.ai/request-id "req-1"}}])
              resolution))
          (.then
           (fn [result]
             (let [attempts (:seon.agent.turn/llm-attempts result)]
               (is (= 2 (count @calls)))
               (is (= 1 (:seon.agent.turn/llm-retries result)))
               (is (= [:provider-error :success]
                      (mapv :seon.ai.attempt/outcome attempts)))
               (is (= ["model-a" "model-a"]
                      (mapv :seon.ai.attempt/requested-model attempts)))
               (is (every? #(= resolution (:seon.ai/config-resolution %))
                           @calls))
               (is (not (str/includes? (pr-str attempts) "user:secret")))
               (is (not (str/includes? (pr-str attempts) "sig=hide"))))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest rate-limit-is-transient
  (async done
    (let [calls (atom [])
          resolution (assoc base-resolution :seon.ai/agent-max-retries 1)]
      (-> (with-compiled-result
            #(invoke-turn!
              (response-fn calls
                           [(http-failure 429 "rate limited") {:text ""}])
              resolution))
          (.then
           (fn [result]
             (is (= 2 (count @calls)))
             (is (= 1 (:seon.agent.turn/llm-retries result)))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest attempt-timeout-aborts-provider-and-does-not-retry
  (async done
    (let [env (.-env js/process)
          saved (aget env "SEON_LLM_ATTEMPT_TIMEOUT_MS")
          calls (atom [])
          aborted (atom 0)
          llm-fn
          (fn [arg]
            (let [signal (:seon.ai/abort-signal arg)]
              (swap! calls conj arg)
              (.addEventListener signal "abort" #(swap! aborted inc)
                                 #js {:once true})
              (js/Promise. (fn [_resolve _reject]))))]
      (aset env "SEON_LLM_ATTEMPT_TIMEOUT_MS" "30")
      (-> (invoke-turn! llm-fn
                        (assoc base-resolution
                               :seon.ai/agent-max-retries 3
                               :seon.ai/agent-attempt-timeout-ms 45))
          (.then
           (fn [result]
             (is (= 1 (count @calls)))
             (is (= 1 @aborted))
             (is (= :error (:seon.agent.turn/status result)))
             (is (not (contains? result :seon.agent.turn/llm-retries)))
             (is (= :outer-timeout
                    (-> result :seon.agent.turn/llm-attempts first
                        :seon.ai.attempt/outcome)))
             (is (= 45
                    (-> result :seon.agent.turn/llm-attempts first
                        :seon.ai.attempt/outer-timeout-ms)))))
          (.finally
           (fn []
             (if (some? saved)
               (aset env "SEON_LLM_ATTEMPT_TIMEOUT_MS" saved)
               (js-delete env "SEON_LLM_ATTEMPT_TIMEOUT_MS"))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest provider-error-retains-truncation-and-usage-evidence
  (async done
    (let [usage {:prompt_tokens 3 :completion_tokens 4093 :total_tokens 4096}
          response
          {:text ""
           :seon.ai/error {:seon.ai/msg "completion limit exhausted"}
           :seon.ai/raw
           {:seon.ai/text ""
            :seon.ai/error {:seon.ai/msg "completion limit exhausted"}
            :seon.ai.openai-compat/finish-reason "length"
            :seon.ai/truncated? true
            :seon.ai/usage usage}}]
      (-> (invoke-turn! (response-fn (atom []) [response])
                        (assoc base-resolution :seon.ai/agent-max-retries 0))
          (.then
           (fn [result]
             (is (= :error (:seon.agent.turn/status result)))
             (is (= (pr-str usage) (:seon.agent.turn/llm-usage result)))
             (let [attempt (first (:seon.agent.turn/llm-attempts result))]
               (is (= :provider-error (:seon.ai.attempt/outcome attempt)))
               (is (= "length" (:seon.ai.attempt/finish-reason attempt)))
               (is (true? (:seon.ai.attempt/truncated? attempt)))
               (is (= (pr-str usage) (:seon.ai.attempt/usage attempt)))
               (is (= :max-completion-tokens
                      (:seon.ai.attempt/completion-limit-field attempt))))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))
