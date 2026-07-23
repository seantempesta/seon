(ns seon.agent.turn-llm-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [my.blob :as blob]
   [seon.agent.turn.llm :as llm]
   [seon.ai.core :as ai]
   [seon.db :as db]))

(def resolution
  {:seon.ai/resolved-config
   {:seon.ai/provider :openai-compat
    :seon.ai/model "portable"
    :seon.ai/base-url "https://user:secret@example.test/v1"
    :seon.config.model-transport/endpoint-cap 256}
   :seon.ai/agent-attempt-timeout-ms 1000})

(deftest persisted-prompt-is-the-phase-recovery-authority
  (let [artifact (str "system" ai/system-boundary "context")]
    (is (= {:seon.ai/system-prompt "system" :seon.ai/ctx "context"}
           (llm/split-persisted-prompt artifact)))
    (is (nil? (llm/split-persisted-prompt "uncommitted reconstruction")))))

(deftest attempt-terminal-evidence-is-portable-and-bounded
  (let [success (llm/attempt-row 0 nil resolution 1000 false
                                 {:text "(+ 1 2)"})
        timeout (llm/attempt-row
                 1 nil resolution 1000 false
                 {:seon.ai/error {:seon.ai/timeout? true
                                  :seon.ai/outer-timeout? true}})]
    (testing "terminal vocabulary is shared by both claimants"
      (is (= :success (:seon.ai.attempt/outcome success)))
      (is (= :outer-timeout (:seon.ai.attempt/outcome timeout))))
    (testing "normalized endpoint evidence never carries URL credentials"
      (is (= "https://example.test/v1/chat/completions"
             (:seon.ai.attempt/endpoint success))))))

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
