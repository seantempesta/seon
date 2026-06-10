(ns seon.ai.deepseek-test
  "Tests for the DeepSeek client's pure surface:
     - request-body carries the thinking toggle (DISABLED by default —
       the API defaults to enabled, which blew the 60s wall-clock
       timeout on 2026-06-10)
     - set-thinking! flips it / adds reasoning_effort
     - set-timeout-ms! roundtrip

   The actual HTTP path (timeout abort, error-as-value envelope) is
   proven live against the real API — see the 2026-06-10 unit report.

   Run interactively via MCP eval:

     (require 'seon.ai.deepseek-test :reload)
     (cljs.test/run-tests 'seon.ai.deepseek-test)"
  (:require
    [cljs.test :refer [deftest is testing use-fixtures]]
    [seon.ai.deepseek :as deepseek]))

;; Restore the runtime knobs after every test — the pod is live and
;; shares these atoms.
(use-fixtures :each
  {:before (fn [] nil)
   :after  (fn []
             (deepseek/set-thinking! false)
             (deepseek/set-timeout-ms! 60000))})

(deftest thinking-disabled-by-default
  (testing "request body sends thinking disabled unless toggled"
    (let [body (deepseek/request-body {:seon.ai/ctx "hi"})]
      (is (= {:type "disabled"} (:thinking body)))
      (is (not (contains? body :reasoning_effort))))))

(deftest set-thinking!-enables
  (testing "set-thinking! true → thinking enabled, no effort key"
    (deepseek/set-thinking! true)
    (let [body (deepseek/request-body {:seon.ai/ctx "hi"})]
      (is (= {:type "enabled"} (:thinking body)))
      (is (not (contains? body :reasoning_effort)))))
  (testing "set-thinking! \"high\" → enabled + reasoning_effort"
    (deepseek/set-thinking! "high")
    (let [body (deepseek/request-body {:seon.ai/ctx "hi"})]
      (is (= {:type "enabled"} (:thinking body)))
      (is (= "high" (:reasoning_effort body)))))
  (testing "set-thinking! false → back to disabled"
    (deepseek/set-thinking! false)
    (is (= {:type "disabled"}
           (:thinking (deepseek/request-body {:seon.ai/ctx "hi"}))))))

(deftest request-body-defaults-and-overrides
  (testing "defaults fill model/temperature/max_tokens; ctx is the user msg"
    (let [body (deepseek/request-body {:seon.ai/ctx "the ctx"})]
      (is (= "deepseek-v4-pro" (:model body)))
      (is (= false (:stream body)))
      (is (= "the ctx" (-> body :messages second :content)))
      (is (= "user" (-> body :messages second :role)))))
  (testing "explicit opts win"
    (let [body (deepseek/request-body {:seon.ai/ctx           "x"
                                       :seon.ai/model         "deepseek-chat"
                                       :seon.ai/temperature   0.2
                                       :seon.ai/max-tokens    99
                                       :seon.ai/system-prompt "sys"})]
      (is (= "deepseek-chat" (:model body)))
      (is (= 0.2 (:temperature body)))
      (is (= 99 (:max_tokens body)))
      (is (= "sys" (-> body :messages first :content))))))

(deftest set-timeout-ms!-roundtrip
  (testing "set-timeout-ms! returns and installs the new value"
    (is (= 1234 (deepseek/set-timeout-ms! 1234)))
    (is (= 1234 @deepseek/!timeout-ms))
    (is (= 60000 (deepseek/set-timeout-ms! 60000)))))
