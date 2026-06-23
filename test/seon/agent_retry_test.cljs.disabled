(ns seon.agent-retry-test
  "The bounded LLM transport retry (agent-robustness unit, 2026-06-11).

   Observed live: a transient DeepSeek `fetch failed` ended the wake
   (`turn N ▸ error [0 llm-error]`) with no retry and no user-visible
   notice. Pins on `seon.agent/ask-and-eval!`:

     - a TRANSPORT-shaped failure (`:seon.ai/transport?` on the error)
       is retried exactly ONCE, after a small backoff, and the result
       carries `:seon.agent.turn/llm-retries 1` — honest record whether
       the retry recovered or not
     - HTTP/processing/timeout errors NEVER retry (one call, no
       llm-retries key — optional = absent)
     - a retry that still fails closes the turn `:error` with the
       visible self-message naming the retry

   Pure-path tests: the error branch and the blank-reply success
   branch never transact (eval-batch! folds over zero parsed forms),
   so no conn fixture is needed.

   Run interactively via MCP eval:
     (require 'seon.agent-retry-test :reload)
     (cljs.test/run-tests 'seon.agent-retry-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing async]]
    [seon.agent :as agent]))

(def ^:private agent-id "AGTretrytest01")        ; 14 chars (:seon.db/id)

(defn- transport-failure []
  {:text ""
   :seon.ai/error {:seon.ai/msg        "DeepSeek fetch failed: fetch failed"
                   :seon.ai/transport? true}})

(defn- http-failure []
  {:text ""
   :seon.ai/error {:seon.ai/msg    "DeepSeek HTTP 400: bad request"
                   :seon.ai/status 400}})

(defn- counting-llm-fn
  "An llm-fn stub returning the responses in `resps` in order (last one
   repeats). `!calls` counts invocations."
  [!calls resps]
  (fn [_ctx]
    (let [n (swap! !calls inc)]
      (js/Promise.resolve (nth resps (dec n) (last resps))))))

(defn- ask! [llm-fn]
  (agent/ask-and-eval!
    {:seon.agent/id              agent-id
     :seon.agent/llm-fn          llm-fn
     :seon.agent/compile-state   nil       ; never touched: zero forms eval'd
     :seon.agent.turn/id-of-turn "TRNretrytest01"
     :seon.agent.turn/prompt-text "ctx"}))

(deftest transport-error-retries-once-then-fails-honestly
  (async done
    (let [!calls (atom 0)]
      (-> (ask! (counting-llm-fn !calls [(transport-failure)]))
          (.then
            (fn [result]
              (testing "exactly TWO calls — one bounded retry, never a loop"
                (is (= 2 @!calls)))
              (testing "the turn still closes :error when the retry fails"
                (is (= :error (:seon.agent.turn/status result)))
                (is (= 0 (:seon.agent/eval-count result))))
              (testing "the retry is on the record"
                (is (= 1 (:seon.agent.turn/llm-retries result)))
                (let [content (-> result :seon.agent.turn/messages first
                                  :seon.agent.message/content)]
                  (is (str/includes? content "(after 1 retry)")
                      "the visible error self-message names the retry")
                  (is (str/includes? content "fetch failed"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest http-error-never-retries
  (async done
    (let [!calls (atom 0)]
      (-> (ask! (counting-llm-fn !calls [(http-failure)]))
          (.then
            (fn [result]
              (is (= 1 @!calls)
                  "HTTP/processing errors are NOT network-shaped — one call only")
              (is (= :error (:seon.agent.turn/status result)))
              (is (not (contains? result :seon.agent.turn/llm-retries))
                  "optional = absent — no retry happened, no key stored")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest transport-error-then-recovery-records-the-retry
  (async done
    (let [!calls (atom 0)]
      ;; blank reply on recovery — the success path with ZERO forms, so
      ;; nothing evals and nothing transacts (pure-path test).
      (-> (ask! (counting-llm-fn !calls [(transport-failure) {:text ""}]))
          (.then
            (fn [result]
              (is (= 2 @!calls))
              (testing "recovered turn is NOT an error"
                (is (nil? (:seon.agent.turn/status result))
                    "no :error status — with-turn! closes it :done"))
              (testing "the recovery still records the retry honestly"
                (is (= 1 (:seon.agent.turn/llm-retries result))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
