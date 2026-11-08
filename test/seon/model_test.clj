(ns seon.model-test
  "The model call over a real socket: a local server (ring-chez, 127.0.0.1) streams a
  chat completion as server-sent events; the call reads until the server ends the stream.

  The model edge as data: a provider's reply body read into the completion (task
  seon.model/budgets-are-data-and-spend-is-measured). Run: bin/test seon.model-test"
  (:require [clojure.test :refer [deftest is testing]] [clojure.core.async :as a] [clojure.data.json :as json]
            [ring-chez.adapter :as adapter] [seon.model :as model] [seon.repl :as repl] [seon.fx :as fx] [seon.turn :as turn] [seon.contract] [clojure.string :as str] [seon.ctx] [seon.error] [seon.cost :as cost] [seon.fixtures :as fixtures]))

(defn- stop-now!
  "Stops a test's local HTTP server at once: no drain wait, so no time decides. See docs/clocks.md#time-decides-nothing-here-clock-reads-named-as-data."
  [server] (adapter/stop-server server {:drain-timeout-ms 0}))

(defn- chunk-line [m] (str "data: " (json/write-str m :escape-unicode false) "\n\n"))

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

(deftest each-eval-carries-its-place-in-the-reply
  ;; task seon.fx/an-effect-streams-what-it-reads: the stream reader files an eval's code under <call>/<k>/eval-0 (k the tool call's :index),
  ;; so its output must file under the same: reply-of puts [k 0] on each eval, as data, and the stream id is made from it, never from the eval's id
  (let [tc (fn [index id code] {:index index :id id :function {:name "eval" :arguments (json/write-str {"intent" "i" "code" code})}})
        r (model/reply-of {:usage {:prompt_tokens 1 :completion_tokens 1}
                           :choices [{:finish_reason "tool_calls" :message {:content "" :tool_calls [(tc 0 "ta" "(+ 1 1)") (tc 1 "tb" "(+ 2 2)") (tc 2 "tc" "(+ 3 3)")]}}]} 1 100)
        calls (:tool-calls r)
        ids (mapv #(#'repl/stream-id {:stream {:call "c" :k (first (:seon.model/at %)) :i (second (:seon.model/at %))}} "out") calls)]
    (is (= [[0 0] [1 0] [2 0]] (mapv :seon.model/at calls)))
    (is (= ["ta" "tb" "tc"] (mapv :id calls)) "each call its own id")
    (is (= ["c/0/eval-0/out" "c/1/eval-0/out" "c/2/eval-0/out"] ids) "three tool calls: each eval's output under its own call")))

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
      (finally (stop-now! server)))))

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
  (testing "a streamed reply's call pieces join by index into its calls, each an eval with its intent, in order; the content is the message"
    (let [c (model/parse-reply
             (apply str (map chunk-line
                             [{:choices [{:delta {:content "Two steps."}}]}
                              {:choices [{:delta {:tool_calls [{:index 0 :id "c1" :function {:name "eval" :arguments "{\"intent\":\"i\",\"code\":"}}]}}]}
                              {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "\"(def x 41)\"}"}}]}}]}
                              {:choices [{:delta {:tool_calls [{:index 1 :id "c2" :function {:name "eval" :arguments "{\"intent\":\"j\",\"code\":\"(inc x)\"}"}}]}}]}
                              {:choices [{:delta {} :finish_reason "tool_calls"}] :usage {:prompt_tokens 9 :completion_tokens 4}}]))
             5.0 64)]
      (is (= [{:id "c1" :code "(def x 41)" :intent "i" :seon.model/at [0 0]} {:id "c2" :code "(inc x)" :intent "j" :seon.model/at [1 0]}] (:tool-calls c)))
      (is (= ["Two steps." "tool_calls"] [(:text c) (:finish-reason c)]))))
  (testing "a call with no content beside it, and a call cut mid-arguments: its arguments as they came"
    (let [c (model/parse-reply "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"eval\",\"arguments\":\"{\\\"code\\\":\\\"(+ 1\"}}]},\"finish_reason\":\"length\"}]}" 1.0 64)]
      (is (= "" (:text c)))
      (is (= ["c" "{\"code\":\"(+ 1"] ((juxt :id :code) (first (:tool-calls c)))) "its arguments as they came")
      (is (re-find #"not run: the arguments are cut off" (:problem (first (:tool-calls c)))) "and nothing runs: the agent is told why"))))

(deftest a-reply-is-its-text-and-a-call-for-each-eval
  ;; docs/model.md#one-tool-eval: the message is the reply's text; each eval is a call of its own, with an intent, answered by a tool message of its own
  (let [provider {:kind :openai-compat :base-url "" :model "m" :api-key-env "" :max-tokens 10}
        call (fn [id args] {:id id :function {:name "eval" :arguments (json/write-str args)}})
        reply (fn [text calls] (model/parse-reply (json/write-str {:choices [{:message {:content text :tool_calls calls} :finish_reason "tool_calls"}]}) 1.0 64))
        c (reply "Doing f, then g." [(call "ca" {:intent "f" :code "(f)"}) (call "cb" {:intent "g" :code "(g)"})])]
    (testing "stored shape: each eval its own call, the message the reply's text"
      (is (= "Doing f, then g." (:text c)))
      (is (= [{:id "ca" :code "(f)" :intent "f" :seon.model/at [0 0]} {:id "cb" :code "(g)" :intent "g" :seon.model/at [1 0]}] (:tool-calls c))))
    (testing "the wire: the text, one call per eval, one tool message per call; a recorded reply round-trips"
      (let [neutral [{:role "assistant" :content "Doing f, then g." :reply 1}
                     {:role "assistant" :content "" :reply 1 :tool-calls [{:id "ca" :code "(f)" :intent "f"}]}
                     {:role "tool" :tool-call-id "ca" :content "{:handle result/e1, :value 1}"}
                     {:role "assistant" :content "" :reply 1 :tool-calls [{:id "cb" :code "(g)" :intent "g"}]}
                     {:role "tool" :tool-call-id "cb" :content "{:handle result/e2, :value 2}"}
                     {:role "assistant" :content "" :tool-calls [{:id "eval-p3" :code "(my.render/render-changes)"}]}
                     {:role "tool" :tool-call-id "eval-p3" :content "{:handle result/p3, :value {:edits []}}"}]
            sent (:messages (model/request-body provider {:messages neutral :task "t/x" :turn 1}))]
        (is (= ["assistant" "tool" "tool" "assistant" "tool"] (mapv :role sent)) "a reply, its two results, Seon's insert and its result")
        (is (= "Doing f, then g." (get-in sent [0 :content])) "the text beside the calls")
        (is (= [{:id "ca" :type "function" :function {:name "eval" :arguments (json/write-str {:intent "f" :code "(f)"})}}
                {:id "cb" :type "function" :function {:name "eval" :arguments (json/write-str {:intent "g" :code "(g)"})}}]
               (get-in sent [0 :tool_calls])))
        (is (= [{:role "tool" :tool_call_id "ca" :content "{:handle result/e1, :value 1}"} {:role "tool" :tool_call_id "cb" :content "{:handle result/e2, :value 2}"}] (subvec sent 1 3)) "each with its handle")
        (is (= "{\"code\":\"(my.render\\/render-changes)\"}" (get-in sent [3 :tool_calls 0 :function :arguments])) "Seon's own: code and no intent")
        (is (= (mapv #(dissoc % :seon.model/at) (:tool-calls c)) (mapv (fn [tc] (#'model/eval-of "eval" (get-in tc [:function :arguments]) (:id tc))) (get-in sent [0 :tool_calls])))
            "what went out reads back as the reply it was")))
    (testing "a reply with text and no call is a message alone"
      (is (nil? (:tool-calls (reply "Hi" nil))))
      (is (= "Hi" (:text (reply "Hi" nil)))))
    (testing "a call that is not this shape is a typed answer to that reply, nothing run"
      (is (re-find #"code is missing" (:problem (first (:tool-calls (reply "" [(call "c" {:message-md "Hi" :evals [{:intent "f" :code "(f)"}]})]))))) "the old shape's call runs nothing")
      (is (re-find #"code is missing" (:problem (second (:tool-calls (reply "Hi" [(call "ca" {:intent "f" :code "(f)"}) (call "cb" {:intent "g"})]))))) "an eval with no code, beside one that reads")
      (is (re-find #"there is one tool, eval; message is not a tool" (:problem (first (:tool-calls (reply "" [{:id "c" :function {:name "message" :arguments "{\"message-md\":\"Hi\"}"}}])))))))))

(deftest a-request-lets-the-model-reply-with-no-call
  ;; docs/render.md (step 0 measured it live: four DeepSeek turns,
  ;; thinking on, all 200, cache hits 87-96% from turn 2): tool_choice auto (DeepSeek refuses
  ;; `required` with thinking on), so a reply may be a message with no call; the eval tool takes its intent and code; the reasoning goes back as it was returned; no ;; reaches the model
  (let [b (model/request-body {:kind :openai-compat :base-url "" :model "m" :api-key-env "" :max-tokens 10 :thinking :enabled}
                              {:messages [{:role "assistant" :content "I read f." :reasoning "r" :tool-calls [{:id "c1" :code "(f)"}]}
                                          {:role "tool" :tool-call-id "c1" :content "{:handle result/e1, :value 1}"}]
                               :task "t/x" :turn 1})]
    (is (= "auto" (:tool_choice b)))
    (is (= [:intent :code] (keys (get-in b [:tools 0 :function :parameters :properties]))) "the intent, then the code: it thinks before the code")
    (is (= ["intent" "code"] (get-in b [:tools 0 :function :parameters :required])) "both required")
    (is (not (str/includes? (pr-str (:tools b)) ";;")) "no ;; in the tool")
    (is (not (contains? b :temperature)) "absent: the provider's default")
    (is (= 0.5 (:temperature (model/request-body {:kind :openai-compat :base-url "" :model "m" :api-key-env "" :max-tokens 10 :temperature 0.5} {:messages [] :task "t/x" :turn 1}))) "set: as configured")
    (is (= {:role "assistant" :content "I read f." :reasoning_content "r"
            :tool_calls [{:id "c1" :type "function" :function {:name "eval" :arguments "{\"code\":\"(f)\"}"}}]}
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
    (is (= 1000 (seon.ctx.wire/messages-tokens [system])) "the fixture's unit: a message is 1,000 tokens")
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
                "character of the shared prefix); each message's parts sized by their lengths (seon.ctx.wire/messages-tokens: "
                "no text copied, so no bytes per character); the error map made once")
     :growth :linear
     :per-n {:ticks 0.8 :bytes 0.0}
     :fixed {:ticks 3000 :bytes 20500}
     :sizes [250 500 1000 10000 100000 1000000]}))

(deftest a-transport-failure-is-answered-to-the-agent
  (let [failure (model/complete {:kind :openai-compat :base-url "http://127.0.0.1:1" :model "m" :api-key-env "HOME" :max-tokens 2048}
                                {:messages [{:role "user" :content "x"}] :task "t/x" :turn 0})
        dest {:agent "boot/1" :op :fx-reply :k 1}
        [st o] (fx/owner-proc (fx/owner-proc {:kind :t :workers 1 :work nil :back nil}) :done {:dest dest :outcome failure :request {:req {:k 1}}})]
    (is (= :seon.model/transport (:seon.error/type failure)))
    (is (= [failure] (map :r (filter #(= dest (:dest %)) (:out o)))) "the owner answers the asker with the failure, once; nothing re-queues it")
    (is (empty? (:queue st)))
    (is (not (re-find #"kept and retried|after a pause" (:doc (meta #'model/transport-error))))
        "the docstring states what happens: the failure is answered to the agent, not kept and retried")))

;; ---- a reply read a line at a time (docs/flow.md#effects-one-owner-and-its-workers-per-kind) ----

(def ^:private emoji "A character outside the BMP." (str (char 0x1F600)))

(def ^:private call-args
  "The first tool call's arguments as the model wrote them: one eval, its intent with a two-byte character escaped and its code with a
  quote, an escaped newline, a two-byte character and one of four bytes written raw."
  (str "{\"intent\":\"look at \\u00e9\",\"code\":\"(str \\\"\u00e9\\\" 1)\\n(prn :" emoji ")\"}"))

(defn- pieces-of [s n] (mapv #(apply str %) (partition-all n s)))

(def ^:private deepseek-stream
  "A realistic DeepSeek stream: reasoning, content (a character of two bytes and one of four in it), a tool call whose arguments
  arrive in 6-character fragments (cut inside escapes and inside a surrogate pair), a second call, the finish and the usage."
  (str (str/join (concat
                  (for [r ["Think" "ing, " "then act."]] (chunk-line {:model "deepseek-flash" :choices [{:index 0 :delta {:reasoning_content r}}]}))
                  (for [c ["Caf\u00e9 " (str emoji " ok")]] (chunk-line {:choices [{:index 0 :delta {:content c}}]}))
                  (map-indexed (fn [i f] (chunk-line {:choices [{:index 0 :delta {:tool_calls [(cond-> {:index 0 :function {:arguments f}}
                                                                                                 (zero? i) (-> (assoc :id "call_0" :type "function") (assoc-in [:function :name] "eval")))]}}]}))
                               (pieces-of call-args 6))
                  [(chunk-line {:choices [{:index 0 :delta {:tool_calls [{:index 1 :id "call_1" :function {:name "eval" :arguments "{\"intent\":\"th"}}]}}]})
                   (chunk-line {:choices [{:index 0 :delta {:tool_calls [{:index 1 :function {:arguments "en\",\"code\":\"(inc 1)\"}"}}]}}]})
                   (chunk-line {:choices [{:index 0 :delta {} :finish_reason "tool_calls"}]})
                   (chunk-line {:choices [] :usage {:prompt_tokens 120 :completion_tokens 30 :prompt_cache_hit_tokens 64}})]))
       "data: [DONE]\n\n"))

(defn- via-reader
  "The lines the edge reader (seon.fx/read-lines!) puts on its channel for body's bytes pulled in the given pieces, up to its end."
  [^bytes bs cuts]
  (let [ps (atom (->> (concat [0] cuts [(alength bs)]) (partition 2 1) (map (fn [[a b]] (java.util.Arrays/copyOfRange bs (int a) (int b))))))
        lines (a/chan)]
    (#'fx/read-lines! (fn [] (let [p (first @ps)] (swap! ps rest) p)) lines nil)
    (loop [acc []] (let [m (a/<!! lines)] (if (:line m) (recur (conj acc (:line m))) [acc m])))))

(defn- fold [lines]
  (reduce (fn [[st out] l] (let [[st' ms] (model/sse-step st l)] [st' (into out ms)])) [(model/sse-init "c1" "boot/1") []] lines))

(deftest a-streamed-reply-cut-at-any-byte-is-the-whole-reply
  ;; the lines are split on the newline byte and each decoded whole; the pieces are cut mid-line and inside a character
  (let [bs (.getBytes ^String deepseek-stream "UTF-8")
        whole (model/parse-reply deepseek-stream 1.0 2048)
        around (fn [i] (some #(or (= 10 %) (neg? %)) (map #(aget bs %) (range (max 0 (- i 3)) (min (alength bs) (+ i 4))))))
        cuts (filter #(or (zero? (mod % 17)) (around %)) (range 1 (alength bs)))]
    (is (= ["eval" "eval"] (map #(get-in % [:function :name]) (get-in (model/sse-reply deepseek-stream) [:choices 0 :message :tool_calls]))))
    (is (= 2 (count (:tool-calls whole))) (pr-str whole))
    (is (= "tool_calls" (:finish-reason whole)))
    (doseq [c cuts
            :let [[lines end] (via-reader bs [c])
                  [st _] (fold lines)]]
      (is (= {:end true} end))
      (when-not (= whole (model/stream-reply st 1.0 2048))
        (is (= whole (model/stream-reply st 1.0 2048)) (str "cut at byte " c))))
    (let [[lines _] (via-reader bs (range 1 (alength bs) 5))]
      (is (= whole (model/stream-reply (first (fold lines)) 1.0 2048)) "and in pieces of five bytes"))))

(deftest what-streams-is-what-is-committed
  (let [[st out] (fold (str/split-lines deepseek-stream))
        whole (model/parse-reply deepseek-stream 1.0 2048)
        args (json/read-str call-args)
        by-id (group-by :stream out)
        ;; a message holds its part's whole text so far (the vector of its pieces): the last one is all of it
        text (fn [id] (apply str (:text (last (filter :text (by-id id))))))]
    (is (= (:reasoning whole) (text "c1/reasoning")))
    (is (= (str "Caf\u00e9 " emoji " ok") (text "c1/content")))
    (is (= "then" (text "c1/1/eval-0/intent")))
    (is (= "(inc 1)" (text "c1/1/eval-0/code")))
    (doseq [k ["intent" "code"]]
      (is (= (get args k) (text (str "c1/0/eval-0/" k))) (str "eval " k)))
    (is (= (get args "code") (:code (first (:tool-calls whole)))) "the committed code is the one streamed")
    (doseq [[id ms] by-id]
      (let [ts (filter :text ms)]
        (is (every? (fn [[a b]] (= a (subvec b 0 (count a)))) (partition 2 1 (map :text ts))) (str id ": each message holds the one before it and more, so a dropped one loses nothing"))
        (is (every? #(= "boot/1" (:agent %)) ms))))
    (is (every? #(<= (count (filter :end %)) 1) (vals by-id)) "an id ends once")
    (is (= #{"c1/0/eval-0/intent" "c1/0/eval-0/code" "c1/1/eval-0/intent" "c1/1/eval-0/code"}
           (set (map :stream (filter :end out))))
        "each tool-call string ends as it closes")
    (is (= #{"c1/reasoning" "c1/content"} (set (map :stream (model/sse-close st)))) "the call's end closes what is still open")
    ;; the container and the part are data on every message, so no reader splits an id (owner: "no hacky parsing")
    (is (every? #(= "c1" (:in %)) (concat out (model/sse-close st))) "every message names its call")
    (is (= {"c1/reasoning" "reasoning" "c1/content" "content" "c1/0/eval-0/intent" "intent"
            "c1/0/eval-0/code" "code" "c1/1/eval-0/intent" "intent" "c1/1/eval-0/code" "code"}
           (into {} (map (juxt :stream :part)) (concat out (model/sse-close st))))
        "every message names its part")
    (is (= [] (second (reduce (fn [[st out] l] (let [[s m] (model/sse-step st l)] [s (into out m)]))
                              [(model/sse-init nil nil) []] (str/split-lines deepseek-stream))))
        "a state with no call streams nothing")))

(deftest the-json-reader-keeps-its-place-between-fragments
  (let [feed (fn [frags] (let [[_ evs] (reduce (fn [[st evs] f] (let [[st' e] (model/json-feed st f)] [st' (into evs e)])) [nil []] frags)]
                           (->> evs (filter (comp string? second)) (group-by first) (map (fn [[p xs]] [p (apply str (map second xs))])) (into {}))))
        doc "{\"a\":\"x\\\"y\",\"k\":\"\\u00e9\\ud83d\\ude00!\",\"b\\n\":\"z\"}"]
    (is (= {"a" "x\"y" "k" (str "\u00e9" emoji "!") "b\n" "z"} (feed [doc])))
    (is (= (feed [doc]) (feed (map str doc))) "one character at a time: an escape, a \\uXXXX and a pair cut anywhere")
    (is (= {"b\n" "z"} (feed (pieces-of "{\"b\\n\":\"z\"}" 3))) "a key is not a value")))

(defn fold-stream
  "The cost proof's function: n tool-call fragment lines through the line step and the close."
  {:malli/schema [:=> [:cat [:vector :string]] :int]}
  [lines]
  (let [[st out] (fold lines)] (+ (count out) (count (model/sse-close st)))))

(defn fragment-lines
  "The cost proof's input at n: [lines], a tool call's first line then n lines of 8 more characters of its eval's code."
  [n]
  [(into [(str "data: " (json/write-str {:choices [{:delta {:tool_calls [{:index 0 :id "c" :function {:name "eval" :arguments "{\"intent\":\"i\",\"code\":\""}}]}}]}))]
         (repeat n (str "data: " (json/write-str {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "(+ 1 2) "}}]}}]}))))])

(deftest a-line-costs-its-bytes-not-the-lines-before-it
  ;; the line step folds one line into a state that grows by pieces: no copy of what came before
  (cost/proof
    {:of 'seon.model-test/fold-stream :input 'seon.model-test/fragment-lines
     :n "lines of a tool call's arguments, each 8 characters"
     :work "per line: its JSON read, its fragment appended to the call's pieces, its 8 characters decoded by the string reader and sent as one message"
     :growth :linear
     :per-n {:calls 5 :ticks 8800 :bytes 28500}
     :fixed {:calls 20 :ticks 20000 :bytes 70000}
     :sizes [10 100 1000]}))

(deftest a-call-that-names-itself-is-answered-as-a-stream-over-the-socket
  ;; http-post's streaming answer, end to end on a local server: the pieces, the step and the final make the completion the whole read does
  (let [port (+ 20000 (rand-int 20000))
        server (adapter/run-server
                (fn [_] (let [out (a/chan 16)]
                          (a/thread (doseq [c chunks] (a/>!! out c)) (a/close! out))
                          {:status 200 :headers {"Content-Type" "text/event-stream"} :body out}))
                {:port port :host "127.0.0.1" :strategy :fibers})]
    (try
      (let [provider {:kind :openai-compat :base-url (str "http://127.0.0.1:" port) :model "deepseek-flash" :api-key-env "HOME" :max-tokens 2048}
            req {:messages [{:role "user" :content "square?"}] :task "t/sq" :turn 0}
            {:keys [pull shut init step final close] :as s} (:stream (model/complete provider (assoc req :call "c1" :agent "boot/1")))
            lines (let [ls (a/chan)] (#'fx/read-lines! pull ls shut)
                    (loop [acc []] (let [m (a/<!! ls)] (if (:line m) (recur (conj acc (:line m))) acc))))
            [st out] (reduce (fn [[st out] l] (let [[st' ms] (step st l)] [st' (into out ms)])) [init []] lines)
            r (final st)]
        (is (some? s) "the call is answered at the start of its reply")
        (is (= (:text (model/complete provider req)) (:text r)) "and its last state is the reply the whole read makes")
        (is (= [["Square "] ["Square " "multiplies."]] (map :text (filter (comp #{"c1/reasoning"} :stream) out))))
        (is (nil? (shut)) "the connection is closed already, and closing again is nothing"))
      (finally (stop-now! server)))))
