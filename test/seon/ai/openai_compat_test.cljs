(ns seon.ai.openai-compat-test
  "OpenAI-compatible request, response, and SDK boundary tests."
  (:require
   ["openai" :as OpenAI]
   [cljs.test :refer [async deftest is testing]]
   [clojure.string :as str]
   [seon.ai :as ai]
   [seon.ai.openai-compat :as openai]))

(def ^:private response-cap 64)
(def ^:private endpoint-cap 256)

(defn- resolution
  ([] (resolution {}))
  ([config-row]
   (ai/resolved-config-from-rows
    (merge {:seon.config.model-transport/response-identity-cap response-cap
            :seon.config.model-transport/endpoint-cap endpoint-cap}
           config-row)
    {})))

(def ^:private deepseek-resolution (resolution {}))

(defn- params
  ([request] (params request deepseek-resolution))
  ([request resolved] (openai/request-params request resolved)))

(defn- complete
  ([request] (complete request deepseek-resolution))
  ([request resolved]
   (openai/complete (assoc request :seon.ai/config-resolution resolved))))

(deftest request-params-default-shape
  (is (= {:model "deepseek-v4-pro"
          :messages [{:role "system" :content "sys"}
                     {:role "user" :content "the ctx"}]
          :temperature 0.7
          :max_tokens 4096
          :stream_options {:include_usage true}
          :thinking {:type "disabled"}}
         (params {:seon.ai/ctx "the ctx" :seon.ai/system-prompt "sys"}))))

(deftest resolved-config-and-request-overrides-drive-params
  (let [resolved (resolution {:seon.ai/model "deepseek-chat"
                              :seon.ai/temperature 0.2
                              :seon.ai/max-tokens 99
                              :seon.ai/thinking "high"})
        body (params {:seon.ai/ctx "hi"} resolved)
        override (params {:seon.ai/ctx "x"
                          :seon.ai/model "deepseek-v4-pro"
                          :seon.ai/temperature 0.9
                          :seon.ai/max-tokens 7}
                         resolved)]
    (is (= "deepseek-chat" (:model body)))
    (is (= 0.2 (:temperature body)))
    (is (= 99 (:max_tokens body)))
    (is (= {:type "enabled"} (:thinking body)))
    (is (= "high" (:reasoning_effort body)))
    (is (= "deepseek-v4-pro" (:model override)))
    (is (= 0.9 (:temperature override)))
    (is (= 7 (:max_tokens override)))))

(deftest compatible-gateways-use-only-standard-reasoning-effort
  (let [effort (params {:seon.ai/ctx "hi"}
                       (resolution {:seon.ai/provider :openai-compat
                                    :seon.ai/base-url "https://gw.example/v1"
                                    :seon.ai/thinking "minimal"}))
        enabled (params {:seon.ai/ctx "hi"}
                        (resolution {:seon.ai/provider :openai-compat
                                     :seon.ai/base-url "https://gw.example/v1"
                                     :seon.ai/thinking "true"}))]
    (is (= "minimal" (:reasoning_effort effort)))
    (is (not (contains? effort :thinking)))
    (is (not (contains? enabled :reasoning_effort)))
    (is (not (contains? enabled :thinking)))))

(deftest tools-and-extra-body-stay-at-their-own-seams
  (let [tools [{:type "function" :function {:name "f" :parameters {}}}]
        absent (params {:seon.ai/ctx "hi"})
        present (params {:seon.ai/ctx "hi"
                         :seon.ai/tools tools
                         :seon.ai/tool-choice "auto"
                         :seon.ai/extra-body
                         {:chat_template_kwargs {:enable_thinking false}}})]
    (is (not (contains? absent :tools)))
    (is (= tools (:tools present)))
    (is (= "auto" (:tool_choice present)))
    (is (not (contains? present :chat_template_kwargs)))
    (is (not (contains? present :extra-body)))))

(def ^:private evidence-resolution
  (resolution {:seon.ai/provider :deepseek}))

(deftest parse-completion-retains-bounded-response-data
  (let [response
        (openai/parse-completion
         #js{:id "req-1" :model "model-a" :system_fingerprint "fp-a"
             :choices #js[#js{:message #js{:content "ok"
                                           :tool_calls #js[#js{:id "t1"}]}
                               :finish_reason "stop"}]
             :vendor_field "preserved"}
         evidence-resolution)]
    (is (= "ok" (:seon.ai/text response)))
    (is (= "model-a" (:seon.ai/response-model response)))
    (is (= "fp-a" (:seon.ai/system-fingerprint response)))
    (is (= "req-1" (:seon.ai/request-id response)))
    (is (= [{:id "t1"}] (:seon.ai/tool-calls response)))
    (is (= {:vendor_field "preserved"}
           (:seon.ai/provider-fields response)))))

(deftest oversized-response-data-does-not-discard-the-completion
  (let [oversized (apply str (repeat (inc response-cap) "m"))
        response
        (openai/parse-completion
         #js{:model oversized
             :system_fingerprint "fp"
             :choices #js[#js{:message #js{:content "ok"}
                               :finish_reason "stop"}]}
         evidence-resolution)]
    (is (= "ok" (:seon.ai/text response)))
    (is (not (contains? response :seon.ai/response-model)))
    (is (= "fp" (:seon.ai/system-fingerprint response)))
    (is (<= (count (:seon.ai/evidence-error response)) response-cap))
    (is (not (str/includes? (pr-str response) oversized)))))

(deftest endpoint-evidence-normalizes-and-redacts-secrets
  (is (= "https://gateway.example/v1/chat/completions"
         (ai/openai-request-endpoint
          "https://user:password@gateway.example/v1?signature=secret#fragment"
          endpoint-cap))))

(defn- with-env [settings body]
  (let [env (.. js/process -env)
        saved (into {} (map (fn [[name _]] [name (aget env name)])) settings)]
    (doseq [[name value] settings]
      (if (some? value) (aset env name value) (js-delete env name)))
    (-> (js/Promise.resolve (body))
        (.finally
         (fn []
           (doseq [[name _] settings]
             (if-some [value (get saved name)]
               (aset env name value)
               (js-delete env name))))))))

(defn- with-fetch [stub body]
  (set! openai/*fetch* stub)
  (-> (js/Promise.resolve (body))
      (.finally #(set! openai/*fetch* nil))))

(defn- with-stubbed [stub body]
  (with-env {"DEEPSEEK_API_KEY" "test-key"
             "SEON_AI_API_KEY" nil}
    (fn [] (with-fetch stub body))))

(defn- sse-stream [s]
  (js/ReadableStream.
   #js{:start (fn [controller]
                (.enqueue controller (.encode (js/TextEncoder.) s))
                (.close controller))}))

(defn- chunk-line [m]
  (str "data: " (.stringify js/JSON (clj->js m)) "\n\n"))

(def ^:private usage-fixture
  {:prompt_tokens 3 :completion_tokens 5 :total_tokens 8})

(defn- sse-completion
  ([] (sse-completion {} nil))
  ([extra-top tool-calls]
   (let [identity (select-keys extra-top [:id :model :system_fingerprint])]
     (str (chunk-line
           (merge {:id "x" :object "chat.completion.chunk" :created 1
                   :model "m"
                   :choices [{:index 0
                              :delta (cond-> {:role "assistant" :content "hi"}
                                       tool-calls (assoc :tool_calls tool-calls))
                              :finish_reason nil}]}
                  extra-top))
          (chunk-line
           (merge {:id "x" :object "chat.completion.chunk" :created 1
                   :model "m"
                   :choices [{:index 0 :delta {} :finish_reason "stop"}]}
                  identity))
          (chunk-line
           (merge {:id "x" :object "chat.completion.chunk" :created 1
                   :model "m" :choices [] :usage usage-fixture}
                  identity))
          "data: [DONE]\n\n"))))

(defn- streaming-fetch [captured sse]
  (fn [url init]
    (reset! captured
            {:url url
             :auth (some-> init .-headers (.get "authorization"))
             :signal (.-signal init)
             :body (js->clj (.parse js/JSON (.-body init))
                           :keywordize-keys true)})
    (js/Promise.resolve
     (js/Response. (sse-stream sse)
                   #js{:status 200
                       :headers #js{"content-type" "text/event-stream"}}))))

(deftest complete-streams-text-usage-and-extra-body
  (async done
    (let [captured (atom nil)]
      (-> (with-stubbed
            (streaming-fetch captured (sse-completion))
            (fn []
              (complete {:seon.ai/ctx "hi"
                         :seon.ai/system-prompt "sys"
                         :seon.ai/extra-body
                         {:chat_template_kwargs {:enable_thinking false}}})))
          (.then (fn [response]
                   (is (= "hi" (:seon.ai/text response)))
                   (is (= usage-fixture (:seon.ai/usage response)))
                   (is (= "stop"
                          (:seon.ai.openai-compat/finish-reason response)))
                   (is (= "Bearer test-key" (:auth @captured)))
                   (is (= {:enable_thinking false}
                          (get-in @captured
                                  [:body :chat_template_kwargs])))
                   (is (some? (get-in @captured [:body :model])))
                   (is (seq (get-in @captured [:body :messages])))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str "threw — " error)) (done)))))))

(deftest one-resolution-survives-later-config-values
  (async done
    (let [captured (atom nil)
          frozen (resolution {:seon.ai/provider :openai-compat
                              :seon.ai/model "model-a"
                              :seon.ai/base-url "https://a.example/v1"
                              :seon.ai/timeout-ms 120000
                              :seon.ai/api-key-env "MODEL_KEY_A"
                              :seon.ai/temperature 0.1
                              :seon.ai/max-tokens 111
                              :seon.ai/thinking "minimal"
                              :seon.ai/extra-body-edn
                              "{:chat_template_kwargs {:enable_thinking false}}"})]
      (-> (with-env {"MODEL_KEY_A" "secret-a"}
            (fn []
              (with-fetch (streaming-fetch captured
                                           (sse-completion {:model "response-a"}
                                                           nil))
                (fn [] (complete {:seon.ai/ctx "frozen"} frozen)))))
          (.then (fn [response]
                   (is (= "https://a.example/v1/chat/completions"
                          (:url @captured)))
                   (is (= "Bearer secret-a" (:auth @captured)))
                   (is (= "model-a" (get-in @captured [:body :model])))
                   (is (= 0.1 (get-in @captured [:body :temperature])))
                   (is (= 111 (get-in @captured [:body :max_tokens])))
                   (is (= "minimal"
                          (get-in @captured [:body :reasoning_effort])))
                   (is (= "response-a" (:seon.ai/response-model response)))
                   (is (not (str/includes? (pr-str (:seon.ai/config-evidence response))
                                           "secret-a")))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str "threw — " error)) (done)))))))

(deftest adapter-preserves-system-prompt-and-attempt-signal
  (async done
    (let [captured (atom nil)
          controller (js/AbortController.)
          signal (.-signal controller)
          request {:seon.ai/ctx "context"
                   :seon.ai/system-prompt "frozen system"
                   :seon.ai/abort-signal signal
                   :seon.ai/config-resolution deepseek-resolution}]
      (-> (with-stubbed
            (fn [_url init]
              (reset! captured {:signal (.-signal init)
                                :body (js->clj (.parse js/JSON (.-body init))
                                              :keywordize-keys true)})
              (.abort controller)
              (js/Promise.reject (js/DOMException. "aborted" "AbortError")))
            (fn [] ((openai/agent-adapter) request)))
          (.then (fn [response]
                   (is (true? (get-in response
                                      [:seon.ai/error :seon.ai/timeout?])))
                   (is (true? (.-aborted (:signal @captured))))
                   (is (= "frozen system"
                          (get-in @captured [:body :messages 0 :content])))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str "threw — " error)) (done)))))))

(deftest sdk-errors-are-classified-without-retries
  (async done
    (let [calls (atom 0)]
      (-> (with-stubbed
            (fn [_ _]
              (swap! calls inc)
              (js/Promise.resolve
               (js/Response. "boom"
                             #js{:status 500
                                 :headers #js{"content-type"
                                              "application/json"}})))
            (fn [] (complete {:seon.ai/ctx "hi"})))
          (.then (fn [response]
                   (is (= 500 (get-in response
                                      [:seon.ai/error :seon.ai/status])))
                   (is (= 1 @calls))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str "threw — " error)) (done)))))))

(deftest timeout-subclass-is-not-a-transport-error
  (testing "classification checks the timeout subclass first"
    (let [classify @#'openai/error->envelope
          timeout (new (.-APIConnectionTimeoutError OpenAI) #js{})
          result (classify "OpenAI-compatible" timeout)]
      (is (true? (:seon.ai/timeout? result)))
      (is (not (contains? result :seon.ai/transport?))))))

(deftest missing-compatible-gateway-config-is-an-error-value
  (async done
    (let [calls (atom 0)
          resolved (resolution {:seon.ai/provider :openai-compat})]
      (-> (with-env {"SEON_AI_API_KEY" "key"}
            (fn []
              (with-fetch (fn [_ _] (swap! calls inc))
                (fn [] (complete {:seon.ai/ctx "hi"} resolved)))))
          (.then (fn [response]
                   (is (= "" (:seon.ai/text response)))
                   (is (zero? @calls))
                   (is (str/includes?
                        (get-in response [:seon.ai/error :seon.ai/msg])
                        ":seon.ai/base-url"))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str "threw — " error)) (done)))))))

(defn- scripted-stream [pieces aborted]
  (let [index (atom 0)
        chunk (fn [content]
                #js{:choices #js[#js{:delta #js{:content content}}]})]
    (js-obj
     "abort" (fn [] (reset! aborted true))
     js/Symbol.asyncIterator
     (fn []
       (js-obj
        "next"
        (fn []
          (js/Promise.resolve
           (let [i @index]
             (if (< i (count pieces))
               (do (swap! index inc)
                   #js{:value (chunk (nth pieces i)) :done false})
               #js{:value js/undefined :done true})))))))))

(deftest stream-aborts-after-the-first-complete-form
  (async done
    (let [aborted (atom false)
          stream (scripted-stream ["(+ 1" " 2)" " fabricated"] aborted)]
      (-> (openai/stream-until-form! stream)
          (.then (fn [response]
                   (is (= "(+ 1 2)" (:seon.ai.openai-compat/text response)))
                   (is (true? (:seon.ai.openai-compat/aborted? response)))
                   (is (true? @aborted))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str "threw — " error)) (done)))))))
