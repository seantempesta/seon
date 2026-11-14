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

(deftest ^{:seon.test/estimate {:ticks 1000000}} a-proof-first-red-specifies-its-task
  ;; plan Stage 1.2 (task seon.ctx.status/a-task-is-specified-once-a-proof-was-red): the first status a turn reads in which one of the task's
  ;; proofs is red writes the task's :seon.task/specified-turn, that turn's number, once: a verdict keeps no history, so the moment it is
  ;; observed is when it is written. No red proof writes nothing; a task that holds it already writes nothing again
  (let [task "app.calc/total-is-exact"
        db (t/with (t/world) [{:seon.agent/key "k1" :seon.agent/task [:seon.task/id task] :seon.agent/turn 3}])
        red {:proofs [{:test 'app.calc-test/total-sums :status :fail} {:test 'app.calc-test/add-adds :status :pass}]}]
    (is (= [{:seon.task/id task :seon.task/specified-turn 3}] (turn/specified-tx db task "k1" red)) "red at turn 3: specified then")
    (is (nil? (turn/specified-tx db task "k1" {:proofs [{:test 'app.calc-test/total-sums :status :undefined}]})) "not written: nothing")
    (is (nil? (turn/specified-tx db task "k1" {:proofs [{:test 'app.calc-test/total-sums :status :pass}]})) "green: nothing")
    (is (nil? (turn/specified-tx (t/with db [{:seon.task/id task :seon.task/specified-turn 2}]) task "k1" red)) "written once")
    (is (nil? (turn/specified-tx (t/with db [{:seon.agent/key "k0" :seon.agent/task [:seon.task/id task]}]) task "k0" red)) "an agent with no turn yet writes nothing: unknown is never 0")))

;; Estimate: in-agent's own setup and teardown (~1.5M: the task file, attach, kill and its branches' deletes) + 1 ask of the test's own
;; at ~0.25M (an eval's recorded answer decoded); the turn's observe, status and draw run in the agent's process, not this one = 2000000;
;; bytes: the answer's decode and the harness's maps, ~2 MB; seconds: the agent's attach and its eval round trip, ~2 s of waiting
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :bytes 2000000 :asks 1}} a-turn-sends-its-model-call-and-returns
  ;; task seon.turn/a-turn-is-a-flow-step (docs/flow.md#the-step-fn-contract-every-proc-follows: a call out is an output, its answer a
  ;; message): turn-begin, on a run whose status asks the model (a task with no proofs: its reason :no-proofs), answers the model call's
  ;; request as its output and returns. The call is opened and its request drawn (its sent hash written), and nothing is sent: no
  ;; effect is out and no reply or error is recorded on it
  (let [[r] (at/in-agent (fn [{:keys [task]}]
                           [(str "(let [[st out] (seon.turn/turn-begin (seon.turn/start! {:task " (pr-str task) " :max-turns 3}))"
                                 "      call (:call (:model out))"
                                 "      m (datahike.api/pull (seon.agent/db) [:seon.model-call/sent-hash] [:seon.model-call/id call])]"
                                 "  {:out (set (keys out)) :messages (count (:messages (:model out))) :pending (= call (:id (:pending st)))"
                                 "   :sent-hash? (some? (:seon.model-call/sent-hash m))"
                                 "   :answered (datahike.api/q '[:find [?m ...] :in $ ?k :where [?a :seon.agent/key ?k] [?m :seon.model-call/agent ?a] [?m :seon.model-call/ms _]] (seon.agent/db) (:key @seon.agent/state))"
                                 "   :fx (count (:fx-pending @seon.agent/state))})")])
                         {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        v (:value r)]
    (is (= #{:model} (:out v)) (str "the model call is the output: " (pr-str r)))
    (is (pos? (:messages v 0)) "its request drawn")
    (is (true? (:pending v)) "and left pending on the run's state, for its answer")
    (is (true? (:sent-hash? v)) "the call opened, its request's bytes hashed")
    (is (= [] (:answered v)) "no call of the agent's has an answer's time (a reply's or an error's): none was made")
    (is (zero? (:fx v -1)) "no effect is out")))

;; Estimate: as seon.agent-test's work-with siblings: the world, built once a process (~3.2M), and one work! loop of 2 turns on it
;; (its start's reads and writes ~0.2M, a turn of a few datoms) = ~4M: 4000000; bytes: the world's seek and the two turns' facts,
;; ~4 MB; seconds: in-process work only, ~10 ms
(deftest ^{:seon.test/estimate {:ticks 4000000 :bytes 4000000}} a-redefined-turn-begin-takes-effect-at-the-next-turn
  ;; task seon.turn/a-turn-is-a-flow-step: work! drives a turn's steps through their vars, so a turn-begin redefined while a run is
  ;; under way (here inside its first turn's model call) is the one the next turn calls, with no restart: the turn boundary is where
  ;; a run's program changes (docs/flow.md#the-step-fn-contract-every-proc-follows)
  (let [orig turn/turn-begin
        end (try (@#'at/work-with (fn [st called!]
                                    (called!)
                                    (alter-var-root #'turn/turn-begin (constantly (fn [st] [:seon.turn/end st :waiting "the redefined turn-begin" nil])))
                                    (update st :turn inc))
                                  (constantly {:reasons [{:reason :proof-red}]}))
                 (finally (alter-var-root #'turn/turn-begin (constantly orig))))]
    (is (= "the redefined turn-begin" (:reason end)) (str "the second turn began with the redefinition: " (pr-str end)))
    (is (= 1 (:model-calls end)) "the first turn ran the turn-begin the run started with")))
