(ns seon.model-test
  "The model call over a real socket: a local server (ring-chez, 127.0.0.1) streams a
  chat completion as server-sent events; the call reads until the server ends the stream.

  The model edge as data: a provider's reply body read into the completion (task
  seon.model/budgets-are-data-and-spend-is-measured). Run: bin/test seon.model-test"
  (:require [clojure.test :refer [deftest is testing]] [clojure.core.async :as a] [clojure.data.json :as json]
            [ring-chez.adapter :as adapter] [seon.model :as model] [seon.turn :as turn] [seon.contract]))

(defn- chunk-line [m] (str "data: " (json/write-str m) "\n\n"))

(def ^:private chunks
  "A DeepSeek-shaped stream: reasoning first, then the content in pieces, the finish
  reason, the usage in its own last chunk, then [DONE]."
  [(chunk-line {:model "deepseek-flash" :choices [{:delta {:reasoning_content "Square "}}]})
   (chunk-line {:model "deepseek-flash" :choices [{:delta {:reasoning_content "multiplies."}}]})
   (chunk-line {:model "deepseek-flash" :choices [{:delta {:content "```clojure\n(defn square "}}]})
   (chunk-line {:model "deepseek-flash" :choices [{:delta {:content "[x] (* x x))\n```"}}]})
   (chunk-line {:model "deepseek-flash" :choices [{:delta {} :finish_reason "stop"}]})
   (chunk-line {:model "deepseek-flash" :choices [] :usage {:prompt_tokens 120 :completion_tokens 30 :prompt_cache_hit_tokens 64}})
   "data: [DONE]\n\n"])

(deftest a-streamed-reply-ends-when-the-server-ends-it
  (let [port (+ 20000 (rand-int 20000))
        seen (promise)
        server (adapter/run-server
                (fn [req]
                  (deliver seen (json/read-str (slurp (:body req)) :key-fn keyword))
                  (let [out (a/chan 16)]
                    ;; the server streams the reply over more than a second, a piece at a time
                    (a/thread (doseq [c chunks] (Thread/sleep 200) (a/>!! out c)) (a/close! out))
                    {:status 200 :headers {"Content-Type" "text/event-stream"} :body out}))
                {:port port :host "127.0.0.1" :strategy :fibers})]
    (try
      (let [r (model/complete {:kind :openai-compat :base-url (str "http://127.0.0.1:" port) :model "deepseek-flash"
                               :api-key-env "HOME" :max-tokens 2048}
                              {:messages [{:role "user" :content "square?"}] :task "t/sq" :turn 0})]
        (is (true? (:stream @seen)) "the request asks for a stream")
        (is (= {:include_usage true} (:stream_options @seen)) "with its usage")
        (is (= "```clojure\n(defn square [x] (* x x))\n```" (:text r)) "every piece, in order, until the server ended it")
        (is (= "Square multiplies." (:reasoning r)))
        (is (= ["stop" "deepseek-flash" 120 30 64] ((juxt :finish-reason :model :prompt-tokens :completion-tokens :cache-hit-tokens) r)))
        (is (< 1000 (:ms r)) "the reply took as long as the server streamed it"))
      (finally (adapter/stop-server server {:drain-timeout-ms 0})))))

(deftest a-dropped-connection-is-a-transport-failure
  (let [r (model/complete {:kind :openai-compat :base-url "http://127.0.0.1:1" :model "m" :api-key-env "HOME" :max-tokens 2048}
                          {:messages [{:role "user" :content "x"}] :task "t/x" :turn 0})]
    (is (= :seon.model/transport (:seon.error/type r)) (pr-str r))))

(deftest a-reply-without-usage-records-no-tokens
  (testing "the provider sent no usage: the completion states no token counts (absent is not 0)"
    (let [c (model/parse-reply "{\"choices\":[{\"message\":{\"content\":\"(+ 1 2)\"}}]}" 12.0 2048)]
      (is (= "(+ 1 2)" (:text c)))
      (is (= #{} (set (filter #{:prompt-tokens :completion-tokens} (keys c)))))))
  (testing "usage sent: both counts, as the provider measured them"
    (let [c (model/parse-reply "{\"choices\":[{\"message\":{\"content\":\"x\"}}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}" 12.0 2048)]
      (is (= [7 3] [(:prompt-tokens c) (:completion-tokens c)])))))

(deftest a-reply-calls-the-one-tool-eval
  ;; owner, 2026-10-22: a turn is native tool calls, one tool, eval, so the model sees each
  ;; evaluation's answer before its next call (DeepSeek's OpenAI-compatible tool_calls)
  (testing "the request declares the one tool, and the conversation's calls and answers go as the API reads them"
    (let [b (model/request-body {:kind :openai-compat :base-url "x" :model "m" :api-key-env "HOME" :max-tokens 64}
                                {:messages [{:role "user" :content "go"}
                                            {:role "assistant" :content "" :tool-calls [{:id "c1" :code "(+ 1 2)"}]}
                                            {:role "tool" :tool-call-id "c1" :content "3"}]
                                 :task "t/x" :turn 1})]
      (is (= ["eval"] (mapv #(get-in % [:function :name]) (:tools b))))
      (is (= [{:id "c1" :type "function" :function {:name "eval" :arguments "{\"code\":\"(+ 1 2)\"}"}}]
             (:tool_calls (second (:messages b)))))
      (is (= {:role "tool" :content "3" :tool_call_id "c1"} (nth (:messages b) 2)))))
  (testing "a streamed reply's call pieces join by index into each call's code, in order"
    (let [c (model/parse-reply
             (apply str (map chunk-line
                             [{:choices [{:delta {:content "Two steps."}}]}
                              {:choices [{:delta {:tool_calls [{:index 0 :id "c1" :function {:name "eval" :arguments "{\"code\":"}}]}}]}
                              {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "\"(def x 41)\"}"}}]}}]}
                              {:choices [{:delta {:tool_calls [{:index 1 :id "c2" :function {:name "eval" :arguments "{\"code\":\"(inc x)\"}"}}]}}]}
                              {:choices [{:delta {} :finish_reason "tool_calls"}] :usage {:prompt_tokens 9 :completion_tokens 4}}]))
             5.0 64)]
      (is (= [{:id "c1" :code "(def x 41)"} {:id "c2" :code "(inc x)"}] (:tool-calls c)))
      (is (= ["Two steps." "tool_calls"] [(:text c) (:finish-reason c)]))))
  (testing "a call with no content beside it, and a call cut mid-arguments: its arguments as they came"
    (let [c (model/parse-reply "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"eval\",\"arguments\":\"{\\\"code\\\":\\\"(+ 1\"}}]},\"finish_reason\":\"length\"}]}" 1.0 64)]
      (is (= "" (:text c)))
      (is (= [{:id "c" :code "{\"code\":\"(+ 1"}] (:tool-calls c))))))

(defn- usage-of
  "The completion a one-document reply with usage u reads as."
  [u]
  (model/parse-reply (json/write-str {:model "m" :choices [{:message {:content "(+ 1 2)"} :finish_reason "stop"}] :usage u}) 1.0 2048))

(deftest a-reply-reports-its-cache-hit-and-miss-tokens
  ;; task seon.model/a-reply-records-its-cached-tokens: whether an attempt cost ~$0.01 or
  ;; ~$0.58 is the provider's cache share, so the completion keeps what the provider reported
  (is (= [64 56] ((juxt :cache-hit-tokens :cache-miss-tokens)
                  (usage-of {:prompt_tokens 120 :completion_tokens 3 :prompt_cache_hit_tokens 64 :prompt_cache_miss_tokens 56})))
      "DeepSeek's hit and miss")
  (is (= {:cache-hit-tokens 64} (select-keys (usage-of {:prompt_tokens 120 :completion_tokens 3 :prompt_tokens_details {:cached_tokens 64}})
                                             [:cache-hit-tokens :cache-miss-tokens]))
      "an OpenAI-compatible cached count; a miss it did not report is absent")
  (is (= {} (select-keys (usage-of {:prompt_tokens 120 :completion_tokens 3}) [:cache-hit-tokens :cache-miss-tokens]))
      "unreported is absent, never 0"))

(deftest a-turn-records-what-the-provider-reported
  (is (= {:seon.turn/tokens 123 :seon.turn/cache-hit-tokens 64 :seon.turn/cache-miss-tokens 56}
         (turn/usage (usage-of {:prompt_tokens 120 :completion_tokens 3 :prompt_cache_hit_tokens 64 :prompt_cache_miss_tokens 56}))))
  (is (= {} (turn/usage (usage-of {}))) "a reply without usage records no count"))


(deftest no-provider-answers-a-typed-error-never-a-reply
  ;; root's ruling, 2026-10-22 (brief 1, item 1): a root whose config names no model provider
  ;; ran the scripted provider (a test fixture) as if it were a model; it answers the first
  ;; turn with the typed :seon.model/no-provider, which ends the attempt naming what to set
  (let [r (model/complete {:kind :none} {:messages [{:role "user" :content "x"}] :task "demo/t" :turn 0})]
    (is (= :seon.model/no-provider (:seon.error/type r)))
    (is (seon.contract/valid? :seon.model/failure r))
    (is (not (contains? r :tool-calls)) "no reply, scripted or otherwise")))

(deftest the-eval-tool-takes-thinking
  ;; owner, 2026-10-23: the eval tool's optional `thinking`, recorded with its call
  (let [props (get-in model/eval-tool [:function :parameters :properties])]
    (is (contains? props :thinking))
    (is (= ["code"] (get-in model/eval-tool [:function :parameters :required])) "thinking stays optional"))
  (let [call-code #'seon.model/call-code]
    (is (= {:code "(+ 1 2)" :thinking "check the sum"} (call-code (json/write-str {:code "(+ 1 2)" :thinking "check the sum"}))))
    (is (= {:code "(+ 1 2)"} (call-code (json/write-str {:code "(+ 1 2)" :thinking " "}))) "blank thinking is none")
    (is (= {:code "{\"code\": \"(+ 1"} (call-code "{\"code\": \"(+ 1")) "a call cut mid-JSON: its arguments as they came"))
  (testing "a recorded call's thinking goes back on the wire with its code"
    (let [m (#'seon.model/wire-message {:role "assistant" :content "" :tool-calls [{:id "c1" :code "(+ 1 2)" :thinking "sum"}]})]
      (is (= {"code" "(+ 1 2)" "thinking" "sum"} (json/read-str (get-in m [:tool_calls 0 :function :arguments])))))))
(deftest cost-prices-a-turn-at-its-models-rates
  (testing "a DeepSeek turn as seon.turn/usage records it: 10k prompt (8k cached), 500 completion"
    (let [u (turn/usage {:prompt-tokens 10000 :completion-tokens 500 :cache-hit-tokens 8000 :cache-miss-tokens 2000})]
      (is (= (/ (+ (* 2000 0.15) (* 8000 0.003) (* 500 0.6)) 1e6) (model/cost "deepseek-flash" u)))
      (is (< (model/cost "deepseek-flash" u) (model/cost "deepseek-v4-pro" u)) "the pro model's rates are higher")))
  (testing "unknown is nil, never 0: no cache split (the scripted fixture's estimate), or no prices"
    (is (nil? (model/cost "deepseek-flash" (turn/usage {:prompt-tokens 100 :completion-tokens 5}))))
    (is (nil? (model/cost "no-such-model" {:seon.turn/tokens 10 :seon.turn/cache-hit-tokens 1 :seon.turn/cache-miss-tokens 1})))
    (is (nil? (model/cost nil {:seon.turn/tokens 10 :seon.turn/cache-hit-tokens 1 :seon.turn/cache-miss-tokens 1})))))
