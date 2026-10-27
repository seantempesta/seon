(ns seon.model-test
  "The model call over a real socket: a local server (ring-chez, 127.0.0.1) streams a
  chat completion as server-sent events; the call reads until the server ends the stream.

  The model edge as data: a provider's reply body read into the completion (task
  seon.model/budgets-are-data-and-spend-is-measured). Run: bin/test seon.model-test"
  (:require [clojure.test :refer [deftest is testing]] [clojure.core.async :as a] [clojure.data.json :as json]
            [ring-chez.adapter :as adapter] [seon.model :as model] [seon.fx :as fx] [seon.turn :as turn] [seon.contract] [clojure.string :as str] [seon.context] [seon.error] [seon.cost]))

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
        (is (= ["stop" "deepseek-flash" 120 30 64] ((juxt :finish-reason :model :prompt-tokens :completion-tokens :cache-hit-tokens) r))))
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
  ;; task seon.model/a-reply-records-its-cached-tokens: whether a run cost ~$0.01 or
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
  ;; turn with the typed :seon.model/no-provider, which ends the run naming what to set
  (let [r (model/complete {:kind :none} {:messages [{:role "user" :content "x"}] :task "demo/t" :turn 0})]
    (is (= :seon.model/no-provider (:seon.error/type r)))
    (is (seon.contract/valid? :seon.model/failure r))
    (is (not (contains? r :tool-calls)) "no reply, scripted or otherwise")))

(deftest cost-prices-a-turn-at-its-models-rates
  (testing "a DeepSeek turn as seon.turn/usage records it: 10k prompt (8k cached), 500 completion"
    (let [u (turn/usage {:prompt-tokens 10000 :completion-tokens 500 :cache-hit-tokens 8000 :cache-miss-tokens 2000})]
      (is (= (/ (+ (* 2000 0.15) (* 8000 0.003) (* 500 0.6)) 1e6) (model/cost "deepseek-flash" u)))
      (is (< (model/cost "deepseek-flash" u) (model/cost "deepseek-v4-pro" u)) "the pro model's rates are higher")))
  (testing "unknown is nil, never 0: no cache split (the scripted fixture's estimate), or no prices"
    (is (nil? (model/cost "deepseek-flash" (turn/usage {:prompt-tokens 100 :completion-tokens 5}))))
    (is (nil? (model/cost "no-such-model" {:seon.turn/tokens 10 :seon.turn/cache-hit-tokens 1 :seon.turn/cache-miss-tokens 1})))
    (is (nil? (model/cost nil {:seon.turn/tokens 10 :seon.turn/cache-hit-tokens 1 :seon.turn/cache-miss-tokens 1})))))

(deftest a-thinking-reply-sends-its-reasoning-back
  ;; DeepSeek's thinking mode with tools (api-docs.deepseek.com/guides/thinking_mode, read
  ;; 2026-10-22): "the reasoning_content must be fully passed back to the API in all subsequent
  ;; requests", else a 400; the reply's reasoning arrives as reasoning_content deltas (above)
  (let [c (model/parse-reply
           (apply str (map chunk-line
                           [{:choices [{:delta {:reasoning_content "Square "}}]}
                            {:choices [{:delta {:reasoning_content "multiplies."}}]}
                            {:choices [{:delta {:tool_calls [{:index 0 :id "c1" :function {:name "eval" :arguments "{\"code\":\"(* 3 3)\"}"}}]}}]}
                            {:choices [{:delta {} :finish_reason "tool_calls"}] :usage {:prompt_tokens 9 :completion_tokens 4}}]))
           1.0 2048)
        b (model/request-body {:kind :openai-compat :base-url "x" :model "m" :api-key-env "HOME" :max-tokens 64 :thinking :enabled}
                              {:messages [{:role "user" :content "go"}
                                          {:role "assistant" :content "" :reasoning (:reasoning c) :tool-calls (:tool-calls c)}
                                          {:role "tool" :tool-call-id "c1" :content "9"}]
                               :task "t/x" :turn 2})]
    (is (= "Square multiplies." (:reasoning c)))
    (is (= "Square multiplies." (:reasoning_content (second (:messages b)))) "the earlier reply's reasoning goes back with it")
    (is (not-any? :reasoning_content [(first (:messages b)) (nth (:messages b) 2)]) "and only on the assistant's message")))

(deftest a-request-lets-the-model-reply-in-prose
  ;; notes/design-agent-view-2026-10-24.md §1 (step 0 measured it live: four DeepSeek turns,
  ;; thinking on, all 200, cache hits 87-96% from turn 2): tool_choice auto (DeepSeek refuses
  ;; `required` with thinking on), so a reply may be prose alone; the eval tool takes code
  ;; only; the reasoning goes back as it was returned; no ;; reaches the model
  (let [b (model/request-body {:kind :openai-compat :base-url "" :model "m" :api-key-env "" :max-tokens 10 :thinking :enabled}
                              {:messages [{:role "assistant" :content "I read f." :reasoning "r" :tool-calls [{:id "c1" :code "(f)"}]}
                                          {:role "tool" :tool-call-id "c1" :content "{:handle result/e1, :value 1}"}]
                               :task "t/x" :turn 1})]
    (is (= "auto" (:tool_choice b)))
    (is (= [:prose :code] (keys (get-in b [:tools 0 :function :parameters :properties]))) "prose first, then code: the model states its intent before the code")
    (is (not (str/includes? (pr-str (:tools b)) ";;")) "no ;; in the tool")
    (is (= {:role "assistant" :content "I read f." :reasoning_content "r"
            :tool_calls [{:id "c1" :type "function" :function {:name "eval" :arguments "{\"code\":\"(f)\"}"}}]}
           (first (:messages b))) "prose, reasoning and the call, as returned")
    (is (= "c1" (:tool_call_id (second (:messages b)))) "the response answers its call by the provider's id")))


(deftest a-turn-whose-cache-misses-its-prefix-is-a-problem
  ;; the shape of the recorded requests (2026-10-25, 94 turn pairs of 6 runs on DeepSeek): the
  ;; system prompt, the task's data, the turn's evals, then the screen, evaluated fresh each turn;
  ;; the provider counts its cache hit in blocks of 128. Each message is 1,000 tokens to the
  ;; sizer (640 words), so every number below is stated, not recomputed
  (let [m (fn [w] {:role "user" :content (subs (str w " " (apply str (repeat 640 "word "))) 0 3200)})
        [system page e1 e2 e3] (map m ["system" "page" "e1" "e2" "e3"])
        t1 [system page e1 e2 (m "screen-a")]
        t2 [system page e1 e2 e3 (m "screen-b")]
        miss? (fn [prev cur prompt hit] (turn/cache-miss prev cur {:prompt-tokens prompt :cache-hit-tokens hit} 128))]
    (is (= 1000 (seon.context/messages-tokens [system])) "the fixture's unit: a message is 1,000 tokens")
    (testing "t2 shares four of its six messages: 4,000 of 6,000 tokens, 3,968 in whole blocks"
      (is (nil? (miss? t1 t2 6000 3968)) "a hit of the shared prefix is no miss")
      (is (nil? (miss? t1 t2 6000 3840)) "one block short is the sizer's error, not a miss")
      (let [e (miss? t1 t2 6000 3839)]
        (is (= :seon.turn/cache-miss (:seon.error/type e)) "more than a block short is a miss")
        (is (= {:hit 3839 :expected 4000 :block 128 :shared 4 :messages 6} (select-keys e [:hit :expected :block :shared :messages])))))
    (testing "the tokens are the provider's count, apportioned: at 9,000 the prefix is 6,000"
      (is (nil? (miss? t1 t2 9000 5760)))
      (is (= 6000 (:expected (miss? t1 t2 9000 5759)))))
    (testing "the prefix ends at the first message that differs"
      (is (= {:shared 2 :expected 2000} (select-keys (miss? [system page (m "e1'") e2] t2 6000 0) [:shared :expected])))
      (is (nil? (miss? [(m "another system")] t2 6000 0)) "nothing shared: no prefix to serve"))
    (testing "turn 1 shares the system prompt and the page only (turn 0's request after them is all screen)"
      (is (nil? (miss? [system page (m "screen-0")] t1 5000 1920))))
    (testing "misses of other sizes are one cause: the agent folds them under one signature, as :effect"
      (let [fold (-> nil (seon.error/note (miss? t1 t2 6000 128) :effect "demo/f#1/2" 1)
                     (seon.error/note (miss? t1 t2 6000 256) :effect "demo/f#1/3" 2))
            [entry] (vals fold)]
        (is (= [1 2 :effect] [(count fold) (:n entry) (:class entry)]))))
    (is (nil? (miss? nil t1 6000 0)) "a run's first request has nothing before it")
    (is (nil? (turn/cache-miss t1 t2 {:prompt-tokens 6000 :cache-hit-tokens 0} 0)) "a provider with no known cache (block 0) is not checked")
    (is (nil? (turn/cache-miss t1 t2 {:prompt-tokens 6000} 128)) "a reply with no cache count is not checked")))

(defn requests
  "The input of size n characters: a request of five messages and the next of six, sharing four
  (n/5 characters each, the shape of a-turn-whose-cache-misses-its-prefix-is-a-problem), with a
  usage and DeepSeek's block."
  [n]
  (let [m (fn [w] {:role "user" :content (subs (str w " " (apply str (repeat (quot n 25) "word "))) 0 (quot n 5))})]
    [(mapv m ["a" "b" "c" "d" "s1"]) (mapv m ["a" "b" "c" "d" "e" "s2"]) {:prompt-tokens 6000 :cache-hit-tokens 0} 128]))

(deftest a-cache-check-costs-its-shared-prefix-not-a-copy
  (seon.cost/proof
    {:of 'seon.turn/cache-miss :input 'seon.model-test/requests
     :n "characters of the request"
     :work (str "the messages compared in order until one differs (= on two equal strings reads both: ~0.8 ticks a "
                "character of the shared prefix); each message's parts sized by their lengths (seon.context/messages-tokens: "
                "no text copied, so no bytes per character); the error map made once")
     :growth :linear
     :per-n {:ticks 0.8 :bytes 0.0}
     :fixed {:ticks 3000 :bytes 20500}
     :sizes [250 500 1000 10000 100000 1000000]}))

(deftest a-transport-failure-is-answered-to-the-agent
  (let [failure (model/complete {:kind :openai-compat :base-url "http://127.0.0.1:1" :model "m" :api-key-env "HOME" :max-tokens 2048}
                                {:messages [{:role "user" :content "x"}] :task "t/x" :turn 0})
        dest {:agent :a1 :op :fx-reply :k 1}
        [st o] (fx/owner-proc (fx/owner-proc {:kind :t :workers 1 :work nil :back nil}) :done {:dest dest :outcome failure :request {:req {:k 1}}})]
    (is (= :seon.model/transport (:seon.error/type failure)))
    (is (= [failure] (map :r (filter #(= dest (:dest %)) (:out o)))) "the owner answers the asker with the failure, once; nothing re-queues it")
    (is (empty? (:queue st)))
    (is (not (re-find #"kept and retried|after a pause" (:doc (meta #'model/transport-error))))
        "the docstring states what happens: the failure is answered to the agent, not kept and retried")))
