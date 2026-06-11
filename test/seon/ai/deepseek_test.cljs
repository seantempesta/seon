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
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing async use-fixtures]]
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

;; ============================================================
;; Error classification (agent-robustness unit, 2026-06-11).
;; `:seon.ai/transport?` marks the ONE retryable class — fetch threw
;; before any HTTP status (the observed live "fetch failed"). HTTP
;; status errors are processing errors and never carry the flag.
;; js/fetch is stubbed per test; the real global is restored after.
;; ============================================================

(defn- with-stubbed-fetch
  "Run `body` (0-arg → Promise) with `js/fetch` replaced by `stub` and
   a test DEEPSEEK_API_KEY; restore both after. Returns a Promise."
  [stub body]
  (let [orig-fetch js/fetch
        orig-key   (.. js/process -env -DEEPSEEK_API_KEY)]
    (set! (.. js/process -env -DEEPSEEK_API_KEY) "test-key")
    (set! js/fetch stub)
    (-> (js/Promise.resolve (body))
        (.finally (fn []
                    (set! js/fetch orig-fetch)
                    (if orig-key
                      (set! (.. js/process -env -DEEPSEEK_API_KEY) orig-key)
                      (js-delete (.-env js/process) "DEEPSEEK_API_KEY")))))))

(deftest fetch-throw-is-transport-shaped
  (async done
    (-> (with-stubbed-fetch
          (fn [_ _] (js/Promise.reject (js/TypeError. "fetch failed")))
          #(deepseek/complete {:seon.ai/ctx "hi"}))
        (.then
          (fn [{:seon.ai/keys [text error]}]
            (is (= "" text) "errors-as-values — empty text, never a rejection")
            (is (true? (:seon.ai/transport? error))
                "fetch threw with no HTTP status → the retryable class")
            (is (not (contains? error :seon.ai/timeout?))
                "a network throw is not a wall-clock abort")
            (is (str/includes? (:seon.ai/msg error) "fetch failed"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest http-status-error-is-not-transport-shaped
  (async done
    (-> (with-stubbed-fetch
          (fn [_ _]
            (js/Promise.resolve
              #js {:ok     false
                   :status 400
                   :text   (fn [] (js/Promise.resolve "bad request"))}))
          #(deepseek/complete {:seon.ai/ctx "hi"}))
        (.then
          (fn [{:seon.ai/keys [text error]}]
            (is (= "" text))
            (is (= 400 (:seon.ai/status error)))
            (is (not (contains? error :seon.ai/transport?))
                "HTTP/processing errors must NEVER look retryable")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
