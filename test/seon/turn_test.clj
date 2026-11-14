(ns seon.turn-test
  "seon.turn's pure parts, on the world (docs/testing.md#testing-in-the-world). Run: bin/test seon.turn-test"
  (:require [clojure.test :refer [deftest is]] [my.test :as t] [seon.turn :as turn] [seon.agent-test :as at]))

(deftest an-agents-tokens-are-its-model-calls-sum
  ;; seon.turn/agent-usage: an agent's model calls and tokens are what its calls recorded, every turn's, a turn that
  ;; threw after its call answered included (the loop's own count lost those: a bench run wrote 111k
  ;; of its calls' 3.54M); a call with no usage counts as a call and adds no tokens, and an agent none of whose calls reported is
  ;; unknown (no :tokens), never 0
  (let [call-of (fn [k n tokens]
                  [(cond-> {:seon.model-call/id (str k "/" n) :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n n :seon.model-call/origin :model}
                     tokens (assoc :seon.model-call/tokens tokens))])
        db (t/with (t/world)
                   (concat (call-of "b1/a1" 1 1000) (call-of "b1/a1" 2 2500) (call-of "b1/a1" 3 nil)
                           (call-of "b1/a2" 1 7)))]
    (is (= {:model-calls 3 :tokens 3500} (turn/agent-usage db "b1/a1"))
        "every call of the agent, the tokens of those that reported, and no other agent's")
    (is (= {:model-calls 1} (turn/agent-usage (t/with (t/world) (call-of "b1/a3" 1 nil)) "b1/a3"))
        "no call reported: its tokens unknown, never 0")))

(deftest an-outside-context-names-only-the-branchs-own-gaps
  ;; docs/turn.md#the-cap: an outside agent's read of its context is the turn the loop would take, at the run's own max-turns: here 7, apart from the config's, which the read once passed as 1
  (let [[_ ctxr bases] (at/in-agent (fn [{:keys [agent]}]
                                   ["(seon.agent/transact! [{:seon.agent/key (:key @seon.agent/state) :seon.agent/max-turns 7}])"
                                    {:op :context :agent agent}
                                    "(mapv (fn [m] (:max-turns (:seon.model-call/basis (datahike.api/pull (seon.agent/db) [:seon.model-call/basis] m)))) (map first (datahike.api/q '[:find ?m :where [?m :seon.model-call/origin :outside]] (seon.agent/db))))"]))]
    (is (= [7] (:value bases)) (str "the read's basis names the run's max-turns: " (pr-str bases) (pr-str ctxr)))))
(deftest ^{:seon.test/estimate {:ticks 200000}} a-hand-back-says-a-failed-run-before-its-unknown-proofs
  ;; docs/render.md#how-the-work-stands: while a test run has failed as a whole (:seon.agent/test-error) no verdict counts, so the done
  ;; rule reads every proof unknown. Seen 11-14: seon_done told a session "ended red saying proof-unknown <proof>" of a proof green at its
  ;; digest, the failed run (one test past its wall bound, the batch no verdict) unsaid. The end says the run first, then the reasons
  (let [s {:reasons [{:reason :proof-unknown :symbol 'app.x-test/t}]
           :test-run {:failed ["app.y-test/slow ended at its wall bound: no verdict"]}}
        [o why] (turn/hand-back-end nil s)]
    (is (= :red o))
    (is (clojure.string/starts-with? why "the test run failed as a whole (app.y-test/slow ended at its wall bound: no verdict), so no verdict counts:")
        (str "the run named first: " why))
    (is (clojure.string/includes? why "proof-unknown app.x-test/t") "then the reasons as before"))
  (is (= [:green nil] (turn/hand-back-end nil {:reasons []})) "a clear status ends green")
  (is (= [:red "proof-red app.x-test/t"] (turn/hand-back-end nil {:reasons [{:reason :proof-red :symbol 'app.x-test/t}]})) "a run that didn't fail says only its reasons")
  (is (= [:failed "gave up"] (turn/hand-back-end {:outcome :failed :reason "gave up"} {:reasons []})) "a failed task ends as it said"))
