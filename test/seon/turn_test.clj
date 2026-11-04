(ns seon.turn-test
  "seon.turn's pure parts, on the world (docs/testing.md#testing-in-the-world). Run: bin/test seon.turn-test"
  (:require [clojure.test :refer [deftest is]] [my.test :as t] [seon.turn :as turn]))

(deftest a-runs-tokens-are-its-model-calls-sum
  ;; seon.turn/run-usage: a run's model calls and tokens are what its calls recorded, every turn's, a turn that
  ;; threw after its call answered included (the loop's own count lost those: a bench run wrote 111k
  ;; of its calls' 3.54M); a call with no usage counts as a call and adds no tokens, and a run none of whose calls reported is
  ;; unknown (no :tokens), never 0
  (let [call-of (fn [run n tokens]
                  [(cond-> {:seon.model-call/id (str "b1/a1/" run "/" n) :seon.model-call/run [:seon.run/id run] :seon.model-call/n n}
                     tokens (assoc :seon.model-call/tokens tokens))])
        db (t/with (t/world)
                   [{:seon.run/id "app.calc/total-is-exact#1"} {:seon.run/id "app.calc/total-is-exact#2"}]
                   (concat (call-of "app.calc/total-is-exact#1" 1 1000) (call-of "app.calc/total-is-exact#1" 2 2500)
                           (call-of "app.calc/total-is-exact#1" 3 nil)
                           (call-of "app.calc/total-is-exact#2" 1 7)))]
    (is (= {:model-calls 3 :tokens 3500} (turn/run-usage db "app.calc/total-is-exact#1"))
        "every call of the run, the tokens of those that reported, and no other run's")
    (is (= {:model-calls 1} (turn/run-usage (t/with (t/world) [{:seon.run/id "x/y#1"}] (call-of "x/y#1" 1 nil)) "x/y#1"))
        "no call reported: its tokens unknown, never 0")))

(deftest a-turn-that-only-repeats-is-named
  ;; bench-scope-1 and -2: the design agent sent the same evals 34 turns running, each told :again, to the turn cap;
  ;; a turn whose every eval ran repeats code with its old answer is one the agent is told of
  (let [again {:again "e1 ran this code and answered the same"}]
    (is (= ["c1" "c2"] (turn/repeated [{:id "c1" :response again} {:id "c2" :response again}])) "every eval it ran repeated")
    (is (nil? (turn/repeated [{:id "c1" :response again} {:id "c2" :response {:value 1}}])) "one new answer is progress")
    (is (= ["c1"] (turn/repeated [{:id "c1" :response again} {:id "c2" :not-run true :response {:ex {}}}])) "a call never run is not counted")
    (is (nil? (turn/repeated [])) "a reply with no call is no repeat")))
