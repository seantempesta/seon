(ns seon.eval-read-test
  "An eval whose text does not read answers a typed error as data, and the next eval answers as any does,
  in process on the REPL's one path (docs/agent.md#your-turn). Run: bin/test seon.eval-read-test"
  (:require [clojure.test :refer [deftest is]] [seon.agent :as agent] [seon.repl :as repl] [seon.fixtures]))

;; Replaces the robust check step's claim that (+ 1 submitted to an agent answers a read error and the
;; agent goes on (docs/agent.md#your-turn): estimate two evals of a few tokens on the REPL path, a
;; few million ticks each (the first ever eval of the process pays the handler's setup).
(deftest ^{:seon.test/estimate {:ticks 15000000}} an-eval-that-does-not-read-answers-an-error-and-the-next-answers-normally
  (let [old @agent/state
        ev (fn [code] (try (reset! agent/state {:n 0 :ns (the-ns 'user)})
                           (repl/eval-string code {})
                           (finally (reset! agent/state old))))
        bad (ev "(+ 1")
        good (ev "(+ 1 1)")]
    (is (= :read (get-in bad [:ex :phase])) "unread text is a read-phase error, as data")
    (is (= :seon.agent/eval-error (get-in bad [:ex :seon.error/type])) "typed")
    (is (re-find #"EOF while reading" (get-in bad [:ex :seon.error/message])) "saying why")
    (is (not (contains? bad :value)) "no value")
    (is (= 2 (:value good)) "the next eval answers as any does")
    (is (nil? (:ex good)))))
