(ns seon.turn-test
  "seon.turn's pure parts, on the world (docs/testing.md#testing-in-the-world). Run: bin/test seon.turn-test"
  (:require [clojure.test :refer [deftest is]] [my.test :as t] [seon.turn :as turn]))

(deftest a-runs-tokens-are-its-model-calls-sum
  ;; seon.turn/run-tokens: a run's tokens are what its model calls recorded, every turn's, a turn that
  ;; threw after its call answered included (the loop's own count lost those: a bench run wrote 111k
  ;; of its calls' 3.54M); a call with no usage is left out, and a run none of whose calls reported is
  ;; unknown (nil), never 0
  (let [turn-of (fn [run n tokens]
                  (cond-> [{:seon.turn/id (str run "/" n) :seon.turn/run [:seon.run/id run] :seon.turn/n n}]
                    tokens (conj {:seon.model-call/id (str run "/" n) :seon.model-call/tokens tokens})))
        db (t/with (t/world)
                   [{:seon.run/id "app.calc/total-is-exact#1"} {:seon.run/id "app.calc/total-is-exact#2"}]
                   (concat (turn-of "app.calc/total-is-exact#1" 0 1000) (turn-of "app.calc/total-is-exact#1" 1 2500)
                           (turn-of "app.calc/total-is-exact#1" 2 nil)
                           (turn-of "app.calc/total-is-exact#2" 0 7)))]
    (is (= 3500 (turn/run-tokens db "app.calc/total-is-exact#1")) "every turn's call that reported, and no other run's")
    (is (nil? (turn/run-tokens (t/with (t/world) [{:seon.run/id "x/y#1"}] (turn-of "x/y#1" 0 nil)) "x/y#1"))
        "no call reported: unknown, never 0")))
