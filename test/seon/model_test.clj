(ns seon.model-test
  "The model call over a real socket: a local server (ring-chez, 127.0.0.1) streams a
  chat completion as server-sent events; the call reads until the server ends the stream.

  The model edge as data: a provider's reply body read into the completion (task
  seon.model/budgets-are-data-and-spend-is-measured). Run: bin/test seon.model-test"
  (:require [clojure.test :refer [deftest is testing]] [clojure.core.async :as a] [clojure.data.json :as json]
            [ring-chez.adapter :as adapter] [seon.model :as model] [seon.fx :as fx] [seon.turn :as turn] [seon.contract] [clojure.string :as str] [seon.context] [seon.error] [seon.cost] [seon.fixtures :as fixtures]))

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

(deftest a-call-that-came-back-as-text-is-answered-not-parked
  ;; deepseek-run4, 2026-11-07: v4-pro's reply was text ending in its own closing tag, finish stop, no call and no opening tag to read one
  ;; from; read as a message it parked all 3 agents silently. It is one eval that says so, so the run goes on, with its usage
  (let [body (fn [content] {:usage {:prompt_tokens 10 :completion_tokens 2}
                            :choices [{:finish_reason "stop" :message {:content content}}]})
        garbled (model/reply-of (body "Reading the two tests next.</｜｜DSML｜｜parameter>\n") 1 100)
        plain (model/reply-of (body "Nothing to run; I wait for you.") 1 100)]
    (is (= ["text-as-call"] (mapv :id (:tool-calls garbled))) (pr-str garbled))
    (is (clojure.string/includes? (:problem (first (:tool-calls garbled))) "came back as text"))
    (is (= 10 (:prompt-tokens garbled)) "its usage is the reply's")
    (is (nil? (:tool-calls plain)) "a reply that is only text stays a reply")))

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
      (is (= [{:id "c1" :type "function" :function {:name "eval" :arguments "{\"message-md\":\"\",\"evals\":[{\"code\":\"(+ 1 2)\"}]}"}}]
             (:tool_calls (second (:messages b)))))
      (is (= {:role "tool" :content "3" :tool_call_id "c1"} (nth (:messages b) 2)))))
  (testing "a streamed reply's call pieces join by index into its call, and its evals are in order, each with its intent"
    (let [c (model/parse-reply
             (apply str (map chunk-line
                             [{:choices [{:delta {:content ""}}]}
                              {:choices [{:delta {:tool_calls [{:index 0 :id "c1" :function {:name "eval" :arguments "{\"message-md\":\"Two steps.\",\"evals\":[{\"intent\":\"i\",\"code\":"}}]}}]}
                              {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "\"(def x 41)\"},{\"intent\":\"j\",\"code\":\"(inc x)\"}]}"}}]}}]}
                              {:choices [{:delta {} :finish_reason "tool_calls"}] :usage {:prompt_tokens 9 :completion_tokens 4}}]))
             5.0 64)]
      (is (= [{:id "c1" :code "(def x 41)" :intent "i"} {:id "c1#1" :code "(inc x)" :intent "j"}] (:tool-calls c)))
      (is (= ["Two steps." "tool_calls"] [(:text c) (:finish-reason c)]))))
  (testing "a call with no content beside it, and a call cut mid-arguments: its arguments as they came"
    (let [c (model/parse-reply "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"eval\",\"arguments\":\"{\\\"evals\\\":[{\\\"code\\\":\\\"(+ 1\"}}]},\"finish_reason\":\"length\"}]}" 1.0 64)]
      (is (= "" (:text c)))
      (is (= ["c" "{\"evals\":[{\"code\":\"(+ 1"] ((juxt :id :code) (first (:tool-calls c)))) "its arguments as they came")
      (is (re-find #"not run: the arguments are cut off" (:problem (first (:tool-calls c)))) "and nothing runs: the agent is told why"))))

(deftest a-reply-is-one-call-and-its-results-one-message
  ;; docs/model.md#one-tool-eval: owner, 2026-11-06: "It's one message-md for ALL the tool calls. each tool call has an intent."
  (let [provider {:kind :openai-compat :base-url "" :model "m" :api-key-env "" :max-tokens 10}
        reply (fn [args & [text]] (model/parse-reply (json/write-str {:choices [{:message {:content (or text "") :tool_calls [{:id "c" :function {:name "eval" :arguments (json/write-str args)}}]} :finish_reason "tool_calls"}]}) 1.0 64))
        args {:message-md "Doing f, then g." :evals [{:intent "f" :code "(f)"} {:intent "g" :code "(g)"}]}
        c (reply args)]
    (testing "stored shape: each eval its own, the message the reply's text"
      (is (= "Doing f, then g." (:text c)))
      (is (= [{:id "c" :code "(f)" :intent "f"} {:id "c#1" :code "(g)" :intent "g"}] (:tool-calls c))))
    (testing "the wire: one assistant call, its arguments as the model sent them; one tool message, its results in order"
      (let [neutral [{:role "assistant" :content "Doing f, then g." :reply 1 :tool-calls [{:id "eval-e1" :code "(f)" :intent "f"}]}
                     {:role "tool" :tool-call-id "eval-e1" :content "{:handle result/e1, :value 1}"}
                     {:role "assistant" :content "" :reply 1 :tool-calls [{:id "eval-e2" :code "(g)" :intent "g"}]}
                     {:role "tool" :tool-call-id "eval-e2" :content "{:handle result/e2, :value 2}"}
                     {:role "assistant" :content "" :tool-calls [{:id "eval-p3" :code "(my.render/render-changes)"}]}
                     {:role "tool" :tool-call-id "eval-p3" :content "{:handle result/p3, :value {:edits []}}"}]
            sent (:messages (model/request-body provider {:messages neutral :task "t/x" :turn 1}))]
        (is (= 4 (count sent)) "a reply and its results, Seon's insert and its result")
        (is (= (json/write-str {:message-md "Doing f, then g." :evals [{:intent "f" :code "(f)"} {:intent "g" :code "(g)"}]})
               (get-in sent [0 :tool_calls 0 :function :arguments])))
        (is (= "" (get-in sent [0 :content])) "no text beside the call")
        (is (= 1 (count (get-in sent [0 :tool_calls]))))
        (is (= {:role "tool" :tool_call_id "eval-e1" :content "{:handle result/e1, :value 1}\n\n{:handle result/e2, :value 2}"} (nth sent 1)) "each with its handle")
        (is (= "{\"message-md\":\"\",\"evals\":[{\"code\":\"(my.render\\/render-changes)\"}]}" (get-in sent [2 :tool_calls 0 :function :arguments])) "Seon's own: no message, code and no intent")))
    (testing "a reply's text is its message when it writes text instead, and no word it wrote is lost"
      (is (= "I wrote text." (:text (reply {:evals [{:intent "f" :code "(f)"}]} "I wrote text."))))
      (is (= "Text.\n\nMessage." (:text (reply {:message-md "Message." :evals [{:intent "f" :code "(f)"}]} "Text.")))))
    (testing "a call that is not this shape is a typed answer to that reply, nothing run"
      (is (re-find #"evals is missing, and the call names code, intent beside message-md" (:problem (first (:tool-calls (reply {:message-md "Hi" :intent "old" :code "(f)"}))))) "an old shape's code is not run, nor taken for a message alone")
      (is (nil? (:tool-calls (reply {:message-md "Hi" :evals []}))) "no evals is a reply of its message alone: no call")
      (is (= "Hi" (:text (reply {:message-md "Hi" :evals []}))))
      (is (re-find #"its code is missing" (:problem (second (:tool-calls (reply {:message-md "Hi" :evals [{:intent "f" :code "(f)"} {:intent "g"}]})))))))))

(deftest a-request-lets-the-model-reply-with-no-call
  ;; docs/render.md (step 0 measured it live: four DeepSeek turns,
  ;; thinking on, all 200, cache hits 87-96% from turn 2): tool_choice auto (DeepSeek refuses
  ;; `required` with thinking on), so a reply may be a message with no call; the eval tool takes its intent and code, and optionally a message; the reasoning goes back as it was returned; no ;; reaches the model
  (let [b (model/request-body {:kind :openai-compat :base-url "" :model "m" :api-key-env "" :max-tokens 10 :thinking :enabled}
                              {:messages [{:role "assistant" :content "I read f." :reasoning "r" :tool-calls [{:id "c1" :code "(f)"}]}
                                          {:role "tool" :tool-call-id "c1" :content "{:handle result/e1, :value 1}"}]
                               :task "t/x" :turn 1})]
    (is (= "auto" (:tool_choice b)))
    (is (= [:message-md :evals] (keys (get-in b [:tools 0 :function :parameters :properties]))) "the message, then the evals: it talks and thinks before the code")
    (is (= [:intent :code] (keys (get-in b [:tools 0 :function :parameters :properties :evals :items :properties]))))
    (is (= ["message-md"] (get-in b [:tools 0 :function :parameters :required])) "only the message: the evals may be none")
    (is (not (str/includes? (pr-str (:tools b)) ";;")) "no ;; in the tool")
    (is (not (contains? b :temperature)) "absent: the provider's default")
    (is (= 0.5 (:temperature (model/request-body {:kind :openai-compat :base-url "" :model "m" :api-key-env "" :max-tokens 10 :temperature 0.5} {:messages [] :task "t/x" :turn 1}))) "set: as configured")
    (is (= {:role "assistant" :content "" :reasoning_content "r"
            :tool_calls [{:id "c1" :type "function" :function {:name "eval" :arguments "{\"message-md\":\"I read f.\",\"evals\":[{\"code\":\"(f)\"}]}"}}]}
           (first (:messages b))) "reply text, reasoning and the call, as returned")
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
