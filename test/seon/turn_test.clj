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

(deftest a-turn-that-only-repeats-is-named
  ;; bench-scope-1 and -2: the design agent sent the same evals 34 turns running, each told :again, to the turn cap;
  ;; a turn whose every eval ran repeats code with its old answer is one the agent is told of
  (let [again {:again "e1 ran this code and answered the same"}]
    (is (= ["c1" "c2"] (turn/repeated [{:id "c1" :response again} {:id "c2" :response again}])) "every eval it ran repeated")
    (is (nil? (turn/repeated [{:id "c1" :response again} {:id "c2" :response {:value 1}}])) "one new answer is progress")
    (is (= ["c1"] (turn/repeated [{:id "c1" :response again} {:id "c2" :not-run true :response {:ex {}}}])) "a call never run is not counted")
    (is (nil? (turn/repeated [])) "a reply with no call is no repeat")))
