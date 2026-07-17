(ns seon.ai.anthropic-test
  "Anthropic request, response, and SDK boundary tests."
  (:require
   ["@anthropic-ai/sdk" :as Anthropic]
   [cljs.test :refer [async deftest is testing]]
   [clojure.string :as str]
   [seon.ai :as ai]
   [seon.ai.anthropic :as anthropic]
   [seon.agent.ctx :as ctx]))

(defn- resolution
  ([] (resolution {}))
  ([config-row]
   (ai/resolved-config-from-rows config-row {})))

(defn- params
  ([request] (params request (resolution {:seon.ai/provider :anthropic})))
  ([request resolved] (anthropic/request-params request resolved)))

(defn- complete
  ([request] (complete request (resolution {:seon.ai/provider :anthropic})))
  ([request resolved]
   (anthropic/complete (assoc request :seon.ai/config-resolution resolved))))

(deftest request-body-default-shape
  (is (= {:model "claude-opus-4-8"
          :max_tokens 16000
          :system [{:type "text" :text "sys"
                    :cache_control {:type "ephemeral"}}]
          :messages [{:role "user" :content "the ctx"}]}
         (params {:seon.ai/ctx "the ctx" :seon.ai/system-prompt "sys"}))))

(deftest resolved-config-and-request-overrides-drive-params
  (let [resolved (resolution {:seon.ai/provider :anthropic
                              :seon.ai/model "claude-fable-5"
                              :seon.ai/max-tokens 2048
                              :seon.ai/temperature 0.3
                              :seon.ai/thinking "high"})
        body (params {:seon.ai/ctx "hi"} resolved)
        override (params {:seon.ai/ctx "hi"
                          :seon.ai/model "claude-sonnet-4-6"
                          :seon.ai/max-tokens 256}
                         resolved)]
    (is (= "claude-fable-5" (:model body)))
    (is (= 2048 (:max_tokens body)))
    (is (= {:type "adaptive"} (:thinking body)))
    (is (not-any? #(contains? body %) [:temperature :top_p :top_k
                                       :reasoning_effort]))
    (is (= "claude-sonnet-4-6" (:model override)))
    (is (= 256 (:max_tokens override)))
    (is (not (contains? (params {:seon.ai/ctx "hi"}
                                (resolution {:seon.ai/provider :anthropic
                                             :seon.ai/thinking "false"}))
                        :thinking)))))

(deftest stable-context-becomes-a-cacheable-system-block
  (let [stable "<system>core</system>"
        volatile "<transcript>tail</transcript>"
        full (str stable "\n\n" ctx/stable-boundary "\n\n" volatile)
        body (params {:seon.ai/ctx full :seon.ai/system-prompt "sys"})]
    (is (= [{:type "text" :text "sys"
             :cache_control {:type "ephemeral"}}
            {:type "text" :text stable
             :cache_control {:type "ephemeral"}}]
           (:system body)))
    (is (= [{:role "user" :content volatile}] (:messages body)))))

(deftest blank-stable-half-keeps-the-context-whole
  (let [full (str "\n\n" ctx/stable-boundary "\n\ntail only")
        body (params {:seon.ai/ctx full :seon.ai/system-prompt "sys"})]
    (is (= 1 (count (:system body))))
    (is (= [{:role "user" :content full}] (:messages body)))))

(defn- msg-obj [m] (clj->js m))

(deftest parse-completion-extracts-text-and-preserves-provider-data
  (let [response
        (anthropic/parse-completion
         (msg-obj {:id "m1" :type "message" :role "assistant"
                   :model "claude-opus-4-8" :stop_reason "tool_use"
                   :content [{:type "thinking" :thinking "hmm" :signature "s"}
                             {:type "text" :text "calling"}
                             {:type "tool_use" :id "t1" :name "f"
                              :input {:x 1}}]
                   :usage {:input_tokens 3 :output_tokens 2}
                   :container {:id "ctr-1"}}))]
    (is (= "calling" (:seon.ai/text response)))
    (is (= "tool_use" (:seon.ai.anthropic/stop-reason response)))
    (is (= [{:type "tool_use" :id "t1" :name "f" :input {:x 1}}]
           (:seon.ai/tool-calls response)))
    (is (= {:container {:id "ctr-1"}} (:seon.ai/provider-fields response)))))

(deftest refusal-is-an-error-value
  (let [response (anthropic/parse-completion
                  (msg-obj {:stop_reason "refusal" :content [] :usage {}}))]
    (is (= "" (:seon.ai/text response)))
    (is (re-find #"refusal" (get-in response [:seon.ai/error :seon.ai/msg])))))

(defn- with-fetch [stub body]
  (set! anthropic/*fetch* stub)
  (-> (js/Promise.resolve (body))
      (.finally #(set! anthropic/*fetch* nil))))

(defn- with-key [body]
  (let [env (.. js/process -env)
        saved (aget env "ANTHROPIC_API_KEY")]
    (aset env "ANTHROPIC_API_KEY" "test-key")
    (-> (js/Promise.resolve (body))
        (.finally #(if (some? saved)
                     (aset env "ANTHROPIC_API_KEY" saved)
                     (js-delete env "ANTHROPIC_API_KEY"))))))

(defn- sse-stream [s]
  (js/ReadableStream.
   #js{:start (fn [controller]
                (.enqueue controller (.encode (js/TextEncoder.) s))
                (.close controller))}))

(def ^:private anth-sse-ok
  (str "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-4-8\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n"
       "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
       "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}\n\n"
       "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
       "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}\n\n"
       "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"))

(defn- streaming-fetch [captured sse]
  (fn [url init]
    (reset! captured {:url url :signal (.-signal init)
                      :body (js->clj (.parse js/JSON (.-body init))
                                    :keywordize-keys true)})
    (js/Promise.resolve
     (js/Response. (sse-stream sse)
                   #js{:status 200
                       :headers #js{"content-type" "text/event-stream"}}))))

(deftest complete-streams-through-the-sdk
  (async done
    (let [captured (atom nil)]
      (-> (with-key (fn []
                      (with-fetch (streaming-fetch captured anth-sse-ok)
                        (fn []
                          (complete {:seon.ai/ctx "hi"
                                     :seon.ai/system-prompt "sys"
                                     :seon.ai/extra-body
                                     {:metadata {:user_id "abc"}}})))))
          (.then (fn [response]
                   (is (= "hello" (:seon.ai/text response)))
                   (is (= "end_turn"
                          (:seon.ai.anthropic/stop-reason response)))
                   (is (str/ends-with? (:url @captured) "/v1/messages"))
                   (is (= {:user_id "abc"}
                          (get-in @captured [:body :metadata])))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str "threw — " error)) (done)))))))

(deftest adapter-preserves-system-prompt-and-abort-signal
  (async done
    (let [captured (atom nil)
          controller (js/AbortController.)
          signal (.-signal controller)
          request {:seon.ai/ctx "context"
                   :seon.ai/system-prompt "frozen system"
                   :seon.ai/abort-signal signal
                   :seon.ai/config-resolution
                   (resolution {:seon.ai/provider :anthropic})}]
      (-> (with-key
            (fn []
              (with-fetch
               (fn [_url init]
                 (reset! captured {:signal (.-signal init)
                                   :body (js->clj (.parse js/JSON (.-body init))
                                                 :keywordize-keys true)})
                 (.abort controller)
                 (js/Promise.reject (js/DOMException. "aborted" "AbortError")))
               (fn [] ((anthropic/agent-adapter) request)))))
          (.then (fn [response]
                   (is (true? (get-in response
                                      [:seon.ai/error :seon.ai/timeout?])))
                   (is (true? (.-aborted (:signal @captured))))
                   (is (= "frozen system"
                          (get-in @captured [:body :system 0 :text])))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str "threw — " error)) (done)))))))

(deftest missing-key-and-http-errors-remain-values
  (async done
    (let [env (.. js/process -env)
          saved (aget env "ANTHROPIC_API_KEY")
          calls (atom 0)]
      (js-delete env "ANTHROPIC_API_KEY")
      (-> (with-fetch (fn [_ _] (swap! calls inc))
            #(complete {:seon.ai/ctx "hi"}))
          (.then (fn [response]
                   (is (= "" (:seon.ai/text response)))
                   (is (zero? @calls))
                   (is (str/includes?
                        (get-in response [:seon.ai/error :seon.ai/msg])
                        "ANTHROPIC_API_KEY"))))
          (.finally #(if (some? saved)
                       (aset env "ANTHROPIC_API_KEY" saved)
                       (js-delete env "ANTHROPIC_API_KEY")))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str "threw — " error)) (done)))))))

(deftest sdk-error-classification
  (testing "connection and status errors remain distinguishable values"
    (let [classify @#'anthropic/error->envelope
          connection (new (.-APIConnectionError Anthropic)
                          #js{:message "offline"})
          status (new (.-APIError Anthropic)
                      429 #js{} "limited"
                      (js/Headers. #js{"retry-after" "2"}))]
      (is (true? (:seon.ai/transport? (classify connection))))
      (is (= 429 (:seon.ai/status (classify status)))))))
