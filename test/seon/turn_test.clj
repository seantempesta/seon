(ns seon.turn-test
  "seon.turn's pure parts, on the world (docs/testing.md#testing-in-the-world). Run: bin/test seon.turn-test"
  (:require [clojure.test :refer [deftest is]] [my.test :as t] [seon.turn :as turn]))

(deftest a-runs-tokens-are-its-model-calls-sum
  ;; seon.turn/run-usage: a run's model calls and tokens are what its calls recorded, every turn's, a turn that
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
    (is (= {:model-calls 2 :tokens 3500} (turn/run-usage db "app.calc/total-is-exact#1"))
        "every turn's call that reported, and no other run's; a turn with no call record is no call")
    (is (= {:model-calls 0} (turn/run-usage (t/with (t/world) [{:seon.run/id "x/y#1"}] (turn-of "x/y#1" 0 nil)) "x/y#1"))
        "no call reported: its tokens unknown, never 0")))
