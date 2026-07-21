(ns seon.agent.turn-fallback-test
  "Behavioral tests for singular per-agent LLM fallback."
  (:require
   [clojure.string :as str]
   [cljs.test :refer [async deftest is testing]]
   [seon.agent.turn :as turn]
   [seon.db :as db]
   [seon.db.branch :as db.branch]
   [seon.db.id :as db.id]
   [seon.execution :as execution]
   [seon.execution.host :as execution.host]))

(def ^:private database-value
  {::db.branch/store-id #uuid "00000000-0000-4000-8000-000000000091"
   ::db.branch/name :db
   ::db.branch/commit-id #uuid "00000000-0000-4000-8000-000000000092"
   ::db.branch/basis-t 42})

(defn- provider-resolution
  [model max-retries attempt-timeout-ms]
  {:seon.ai/resolved-config
   {:seon.ai/provider :openai-compat
    :seon.ai/model model
    :seon.ai/temperature 0.0
    :seon.ai/completion-limit-field :max-completion-tokens
    :seon.ai/base-url (str "https://" model ".example/v1")
    :seon.ai/timeout-ms attempt-timeout-ms}
   :seon.ai/provenance
   {:seon.ai/provider :agent-override
    :seon.ai/model :agent-override
    :seon.ai/temperature :config-row
    :seon.ai/completion-limit-field :config-row
    :seon.ai/base-url :agent-override
    :seon.ai/timeout-ms :agent-override}
   :seon.ai/agent-max-retries max-retries
   :seon.ai/agent-attempt-timeout-ms attempt-timeout-ms})

(def ^:private primary-resolution
  (provider-resolution "primary-model" 1 40))

(def ^:private fallback-resolution
  (provider-resolution "fallback-model" 1 40))

(defn- with-fallback
  [resolution]
  (assoc resolution
         :seon.ai/fallback-variant :muse
         :seon.ai/fallback-config-resolution fallback-resolution))

(defn- http-failure
  [status message]
  {:text ""
   :seon.ai/error {:seon.ai/msg message
                   :seon.ai/status status
                   :seon.ai/retry-after-ms 0}})

(defn- timeout-response
  []
  (js/Promise. (fn [_resolve _reject])))

(defn- scripted-llm
  [calls scripts]
  (fn [request]
    (let [model (get-in request [:seon.ai/config-resolution
                                 :seon.ai/resolved-config
                                 :seon.ai/model])
          model-call-count (count (filter #(= model (:model %)) @calls))
          responses (get scripts model)
          response (nth responses model-call-count (last responses))]
      (swap! calls conj {:model model :request request})
      (if (fn? response)
        (response)
        (js/Promise.resolve response)))))

(defn- with-compiled-result
  [body]
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

(defn- invoke-turn!
  [llm-fn resolution]
  (with-compiled-result
    #(turn/ask-and-eval!
      {:seon.agent/id "fallback-agent"
       :seon.agent/llm-fn llm-fn
       :seon.db/db database-value
       :seon.ai/config-resolution resolution
       :seon.agent.turn/id-of-turn "fallback-turn"
       :seon.agent.turn/prompt-text "immutable prompt"})))

(defn- with-open-turn
  [body]
  (let [original-allocate db.id/allocate!
        original-transact db/transact!
        original-with-context db/with-tx-context
        close-request (atom nil)]
    (set! db.id/allocate!
          (fn [request]
            (let [turn-id "fallback-open-turn"
                  built ((::db.id/transaction-builder request)
                         {::turn/turn-allocation turn-id})]
              (js/Promise.resolve
               {:db-before database-value
                :db-after (assoc database-value ::db.branch/basis-t 43)
                :tx-data (:seon.db/tx-data built)
                :tempids {}
                :tx-meta {}
                ::db.id/ids {::turn/turn-allocation turn-id}
                ::db.id/eids {::turn/turn-allocation 101}}))))
    (set! db/transact!
          (fn [& [request]]
            (reset! close-request request)
            (js/Promise.resolve
             {:db-before (assoc database-value ::db.branch/basis-t 43)
              :db-after (assoc database-value ::db.branch/basis-t 44)
              :tx-data (:seon.db/tx-data request)
              :tempids {}
              :tx-meta {}})))
    (set! db/with-tx-context (fn [_context thunk] (thunk)))
    (-> (js/Promise.resolve (body))
        (.then (fn [result]
                 {:result result
                  :close-row (first (:seon.db/tx-data @close-request))}))
        (.finally
         (fn []
           (set! db.id/allocate! original-allocate)
           (set! db/transact! original-transact)
           (set! db/with-tx-context original-with-context))))))

(defn- attempt-models
  [result]
  (mapv :seon.ai.attempt/requested-model
        (:seon.agent.turn/llm-attempts result)))

(defn- async-failure
  [done error]
  (is false (str error))
  (done))

(deftest timeout-advances-to-fallback-and-closes-the-turn-done
  (async done
    (let [calls (atom [])
          llm-fn (scripted-llm
                  calls
                  {"primary-model" [timeout-response]
                   "fallback-model" [{:text ""}]})]
      (-> (with-open-turn
            (fn []
              (turn/open-turn!
               {:seon.agent/id "fallback-agent"
                :seon.db/db database-value
                :seon.agent.turn/prompt-text "immutable prompt"}
               (fn [turn-id]
                 (with-compiled-result
                   (fn []
                     (turn/ask-and-eval!
                      {:seon.agent/id "fallback-agent"
                       :seon.agent/llm-fn llm-fn
                       :seon.db/db database-value
                       :seon.ai/config-resolution
                       (with-fallback primary-resolution)
                       :seon.agent.turn/id-of-turn turn-id
                       :seon.agent.turn/prompt-text "immutable prompt"})))))))
          (.then
           (fn [{:keys [result close-row]}]
             (is (= ["primary-model" "fallback-model"]
                    (attempt-models result)))
             (is (= [:outer-timeout :success]
                    (mapv :seon.ai.attempt/outcome
                          (:seon.agent.turn/llm-attempts result))))
             (is (= :muse
                    (-> result :seon.agent.turn/llm-attempts second
                        :seon.ai.attempt/fallback-variant)))
             (is (= :done (:seon.agent.turn/status close-row)))))
          (.then (fn [_] (done)))
          (.catch #(async-failure done %))))))

(deftest retryable-exhaustion-advances-to-the-same-fallback-path
  (async done
    (let [calls (atom [])
          llm-fn (scripted-llm
                  calls
                  {"primary-model" [(http-failure 429 "rate limited")]
                   "fallback-model" [{:text ""}]})]
      (-> (invoke-turn! llm-fn (with-fallback primary-resolution))
          (.then
           (fn [result]
             (is (= ["primary-model" "primary-model" "fallback-model"]
                    (attempt-models result)))
             (is (= [:provider-error :provider-error :success]
                    (mapv :seon.ai.attempt/outcome
                          (:seon.agent.turn/llm-attempts result))))
             (is (= [nil nil :muse]
                    (mapv :seon.ai.attempt/fallback-variant
                          (:seon.agent.turn/llm-attempts result))))))
          (.then (fn [_] (done)))
          (.catch #(async-failure done %))))))

(deftest payment-error-never-invokes-fallback
  (async done
    (let [calls (atom [])
          llm-fn (scripted-llm
                  calls
                  {"primary-model" [(http-failure 402 "payment required")]
                   "fallback-model" [{:text ""}]})]
      (-> (invoke-turn! llm-fn (with-fallback primary-resolution))
          (.then
           (fn [result]
             (is (= ["primary-model"] (mapv :model @calls)))
             (is (= :error (:seon.agent.turn/status result)))
             (is (str/includes? (:seon.agent.turn/error result) "402"))))
          (.then (fn [_] (done)))
          (.catch #(async-failure done %))))))

(deftest absent-fallback-preserves-primary-only-exhaustion
  (async done
    (let [calls (atom [])
          llm-fn (scripted-llm
                  calls
                  {"primary-model" [(http-failure 429 "rate limited")]
                   "fallback-model" [{:text ""}]})]
      (-> (invoke-turn! llm-fn primary-resolution)
          (.then
           (fn [result]
             (is (= ["primary-model" "primary-model"]
                    (attempt-models result)))
             (is (= :error (:seon.agent.turn/status result)))
             (is (= 1 (:seon.agent.turn/llm-retries result)))
             (is (every? nil?
                         (map :seon.ai.attempt/fallback-variant
                              (:seon.agent.turn/llm-attempts result))))))
          (.then (fn [_] (done)))
          (.catch #(async-failure done %))))))

(deftest failed-fallback-surfaces-its-error-and-the-full-chain
  (async done
    (let [calls (atom [])
          llm-fn (scripted-llm
                  calls
                  {"primary-model" [(http-failure 429 "primary unavailable")]
                   "fallback-model" [(http-failure 503
                                                   "fallback unavailable")]})]
      (-> (invoke-turn! llm-fn (with-fallback primary-resolution))
          (.then
           (fn [result]
             (is (= ["primary-model" "primary-model" "fallback-model"]
                    (attempt-models result)))
             (is (= :error (:seon.agent.turn/status result)))
             (is (str/includes? (:seon.agent.turn/error result)
                                "fallback unavailable"))
             (is (= [:provider-error :provider-error :provider-error]
                    (mapv :seon.ai.attempt/outcome
                          (:seon.agent.turn/llm-attempts result))))))
          (.then (fn [_] (done)))
          (.catch #(async-failure done %))))))

(deftest composed-attempt-fences-keep-a-failed-chain-bounded
  (async done
    (let [calls (atom [])
          start (.now js/Date)
          bounded-primary (assoc primary-resolution
                                 :seon.ai/agent-max-retries 0
                                 :seon.ai/agent-attempt-timeout-ms 25)
          bounded-fallback (assoc fallback-resolution
                                  :seon.ai/agent-max-retries 1
                                  :seon.ai/agent-attempt-timeout-ms 25)
          resolution (assoc bounded-primary
                            :seon.ai/fallback-variant :muse
                            :seon.ai/fallback-config-resolution
                            bounded-fallback)
          llm-fn (scripted-llm
                  calls
                  {"primary-model" [timeout-response]
                   "fallback-model" [timeout-response]})]
      (-> (invoke-turn! llm-fn resolution)
          (.then
           (fn [result]
             (let [elapsed (- (.now js/Date) start)]
               (testing "one bounded attempt per resolution composes"
                 (is (= ["primary-model" "fallback-model"]
                        (attempt-models result)))
                 (is (= [25 25]
                        (mapv :seon.ai.attempt/outer-timeout-ms
                              (:seon.agent.turn/llm-attempts result))))
                 (is (< elapsed 500)
                     (str "bounded fallback chain took " elapsed "ms"))))))
          (.then (fn [_] (done)))
          (.catch #(async-failure done %))))))
