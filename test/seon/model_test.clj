(ns seon.model-test
  "The model call over a real socket: a local server (ring-chez, 127.0.0.1) streams a
  chat completion as server-sent events; the call reads until the server ends the stream."
  (:require [clojure.test :refer [deftest is]] [clojure.core.async :as a] [clojure.data.json :as json]
            [ring-chez.adapter :as adapter] [seon.model :as model]))

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
                               :api-key-env "HOME"}
                              {:messages [{:role "user" :content "square?"}] :task "t/sq" :turn 0})]
        (is (true? (:stream @seen)) "the request asks for a stream")
        (is (= {:include_usage true} (:stream_options @seen)) "with its usage")
        (is (= "```clojure\n(defn square [x] (* x x))\n```" (:text r)) "every piece, in order, until the server ended it")
        (is (= "Square multiplies." (:reasoning r)))
        (is (= ["stop" "deepseek-flash" 120 30 64] ((juxt :finish-reason :model :prompt-tokens :completion-tokens :cache-hit-tokens) r)))
        (is (< 1000 (:ms r)) "the reply took as long as the server streamed it"))
      (finally (adapter/stop-server server {:drain-timeout-ms 0})))))

(deftest a-dropped-connection-is-a-transport-failure
  (let [r (model/complete {:kind :openai-compat :base-url "http://127.0.0.1:1" :model "m" :api-key-env "HOME"}
                          {:messages [{:role "user" :content "x"}] :task "t/x" :turn 0})]
    (is (= :seon.model/transport (:seon.error/type r)) (pr-str r))))

(deftest a-reply-without-usage-records-no-tokens
  (let [c (model/parse-reply (json/write-str {:choices [{:message {:content "(+ 1 2)"} :finish_reason "stop"}]}) 3.0 2048)]
    (is (= "(+ 1 2)" (:text c)))
    (is (not-any? #(contains? c %) [:prompt-tokens :completion-tokens]) "the provider counted nothing: absent, never 0"))
  (let [port (+ 20000 (rand-int 20000))
        server (adapter/run-server
                (fn [_] {:status 200 :headers {"Content-Type" "text/event-stream"}
                         :body (str (chunk-line {:choices [{:delta {:content "(+ 1 2)"} :finish_reason "stop"}]}) "data: [DONE]\n\n")})
                {:port port :host "127.0.0.1" :strategy :fibers})]
    (try
      (let [r (model/complete {:kind :openai-compat :base-url (str "http://127.0.0.1:" port) :model "m" :api-key-env "HOME"}
                              {:messages [{:role "user" :content "add one and two, please"}] :task "t/x" :turn 0})]
        (is (true? (:estimated? r)) "the call's spend is an estimate, and says so")
        (is (= [6 2] ((juxt :prompt-tokens :completion-tokens) r)) "estimated from the text: 23 and 7 characters at 4 a token"))
      (finally (adapter/stop-server server {:drain-timeout-ms 0})))))
