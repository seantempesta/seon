(ns seon.agent.turn-llm-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [my.blob :as blob]
   [seon.agent.turn.llm :as llm]
   [seon.ai.core :as ai]
   [seon.db :as db])
  #?(:clj
     (:import [java.util.concurrent CountDownLatch TimeUnit])))

(def resolution
  {:seon.ai/resolved-config
   {:seon.ai/provider :openai-compat
    :seon.ai/model "portable"
    :seon.ai/base-url "https://user:secret@example.test/v1"
    :seon.config.model-transport/endpoint-cap 256
    :seon.config.model-transport/response-identity-cap 128}
   :seon.ai/agent-attempt-timeout-ms 1000})

(deftest persisted-prompt-is-the-phase-recovery-authority
  (let [artifact (str "system" ai/system-boundary "context")]
    (is (= {:seon.ai/system-prompt "system" :seon.ai/ctx "context"}
           (llm/split-persisted-prompt artifact)))
    (is (nil? (llm/split-persisted-prompt "uncommitted reconstruction")))))

(deftest attempt-terminal-evidence-is-portable-and-bounded
  (let [success (llm/attempt-row 0 nil resolution 1000 false :batch
                                 {:text "(+ 1 2)"})
        timeout (llm/attempt-row
                 1 nil resolution 1000 false :first-form
                 {:seon.ai/error {:seon.ai/timeout? true
                                  :seon.ai/outer-timeout? true}})
        successful-status
        (llm/attempt-row 2 nil resolution 1000 false :batch
                         {:text "(+ 1 2)" :seon.ai/status 200})
        cause (apply str (repeat 40 "diagnostic-"))
        failed
        (llm/attempt-row
         3 nil resolution 1000 false :batch
         {:seon.ai/error
          {:seon.ai/msg cause
           :seon.ai/status 503
           :seon.ai/transport? true
           :seon.ai/timeout? true
           :seon.ai/retry-after-ms 25
           :seon.ai/raw-body cause
           :seon.ai/exception-class "java.io.IOException"
           :seon.ai/exception-message cause}})]
    (testing "terminal vocabulary is shared by both claimants"
      (is (= :success (:seon.ai.attempt/outcome success)))
      (is (= :outer-timeout (:seon.ai.attempt/outcome timeout))))
    (testing "normalized endpoint evidence never carries URL credentials"
      (is (= "https://example.test/v1/chat/completions"
             (:seon.ai.attempt/endpoint success))))
    (testing "provider causes remain flat, classified, and bounded"
      (is (= 200 (:seon.ai.attempt/response-status successful-status)))
      (is (= :adapter-timeout (:seon.ai.attempt/outcome failed)))
      (is (= 503 (:seon.ai.attempt/error-status failed)))
      (is (true? (:seon.ai.attempt/transport? failed)))
      (is (true? (:seon.ai.attempt/timeout? failed)))
      (is (= 25 (:seon.ai.attempt/retry-after-ms failed)))
      (is (= "java.io.IOException"
             (:seon.ai.attempt/exception-class failed)))
      (is (= 128 (count (:seon.ai.attempt/error-message failed))))
      (is (= 128 (count (:seon.ai.attempt/exception-message failed))))
      (is (= 128 (count (:seon.ai.attempt/error-body failed)))))))

(deftest run-deadline-shortens-the-frozen-attempt-bound
  (let [now #?(:clj (java.util.Date. 1000) :cljs (js/Date. 1000))
        deadline (llm/attempt-deadline
                  {:seon.agent.run/deadline
                   #?(:clj (java.util.Date. 1250) :cljs (js/Date. 1250))}
                  now 1000)]
    (is (= 250 (llm/remaining-ms now deadline)))))

#?(:clj
   (deftest phase-entry-refuses-to-reconstruct-a-missing-prompt
     (with-redefs [db/as-of (fn [database _] database)]
       (binding [blob/*leaf*
                 {:my.blob/get
                  (fn [_]
                    {:my.blob/ok? false :my.blob/error "missing prompt"})}]
         (let [result
               (llm/llm-phase!
                {:seon.agent.driver/run
                 {:seon.agent/id "agent"
                  :seon.agent.run/id "run"
                  :seon.agent.run/current-turn
                  {:seon.agent.turn/id "turn"
                   :seon.agent.turn/rendered-tx 1
                   :seon.agent.turn/prompt-blob {:my.blob/hash "missing"}}}
                 :seon.agent.run/claim-epoch 1
                 :seon.db/db ::database
                 :seon.agent.turn/resolve-context!
                 (fn [_ _ _] {:seon.ai/config-resolution resolution})
                 :seon.agent.turn/now! #(java.util.Date.)
                 :seon.agent.turn/transport!
                 (fn [_] (throw (ex-info "must not dispatch" {})))})]
           (is (= "missing prompt" (:seon.error/message result)))
           (is (= :core-bug (:seon.error/kind result))))))))

#?(:clj
   (deftest presentation-backpressure-never-blocks-prefix-offers
     (let [entered (CountDownLatch. 1)
           release (CountDownLatch. 1)
           published (CountDownLatch. 2)
           values (atom [])
           sink
           (llm/presentation-sink
            1
            (fn [prefix]
              (swap! values conj prefix)
              (.countDown published)
              (when (= "A" prefix)
                (.countDown entered)
                (.await release 2 TimeUnit/SECONDS))))
           offer! (:seon.ai.presentation/offer! sink)]
       (offer! "A")
       (is (.await entered 1 TimeUnit/SECONDS))
       (let [started (System/nanoTime)]
         (doseq [prefix ["AB" "ABC" "ABCD"]]
           (offer! prefix))
         (is (< (/ (- (System/nanoTime) started) 1000000.0) 50.0)
             "offers remain constant-time while publication is blocked"))
       (.countDown release)
       (is (.await published 1 TimeUnit/SECONDS))
       ((:seon.ai.presentation/close! sink))
       (is (= ["A" "ABCD"] @values)
           "only the newest pending prefix survives backpressure"))))
