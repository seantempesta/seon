(ns seon.turn-test
  "seon.turn's pure parts, on the world (docs/testing.md#testing-in-the-world). Run: bin/test seon.turn-test"
  (:require [clojure.test :refer [deftest is]] [my.test :as t] [seon.turn :as turn] [seon.agent-test :as at] [seon.agent :as agent]
            [seon.repl :as repl] [seon.lib :as lib] [clojure.core.async.flow :as flow]))

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

;; Estimate: in-agent's setup and teardown ~1.5M + 5 asks at ~0.25M (root's write on main, a merge of main, one write, one context read, one scoped query of a few seeks) = ~2.8M:
;; 3000000 ticks, ~20 MB
(deftest ^{:seon.test/estimate {:ticks 3000000 :bytes 20000000}} an-outside-context-names-only-the-branchs-own-gaps
  ;; docs/turn.md#the-cap: an outside agent's read of its context is the turn the loop would take, at the run's own max-turns: here 7, apart from the config's, which the read once passed as 1
  ;; another agent's outside read in the same db (as one a test before it on a shared root leaves) is not this agent's: the read is scoped to its own,
  ;; and its newest (the attach takes a read of its own before max-turns is set). :seon.agent/max-turns is root's, written on main (docs/users.md: one writer per
  ;; attribute, the agent's write refusing it), and the branch holds it once it merges main
  (let [[_ _ written ctxr bases] (at/in-agent (fn [{:keys [agent]}]
                                   [{:op :eval :agent "root" :code (str "@(c/transact-main! sys [{:seon.agent/key " (pr-str (name agent)) " :seon.agent/max-turns 7}])")}
                                    "(seon.agent/merge-main! agent)"
                                    "(seon.agent/transact! agent [{:db/id \"other\" :seon.agent/key \"other-outside\"} {:seon.model-call/id \"other-outside-1\" :seon.model-call/n 1 :seon.model-call/origin :outside :seon.model-call/agent \"other\" :seon.model-call/basis {:commit (datahike.api/commit-id (seon.agent/db)) :task (seon.agent/task-of agent) :turn 1 :max-turns 3 :origin :outside :agent \"other-outside\"}}])"
                                    {:op :context :agent agent}
                                    "(let [[_ m] (last (sort (datahike.api/q '[:find ?n ?m :in $ ?k :where [?a :seon.agent/key ?k] [?m :seon.model-call/agent ?a] [?m :seon.model-call/origin :outside] [?m :seon.model-call/n ?n]] (seon.agent/db) (:key agent))))] [(:max-turns (:seon.model-call/basis (datahike.api/pull (seon.agent/db) [:seon.model-call/basis] m)))])"]))]
    (is (nil? (:ex written)) (str "the setup's write: " (pr-str (:ex written))))
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
  (is (= [:failed "gave up"] (turn/hand-back-end {:outcome :failed :reason "gave up"} {:reasons []})) "a failed task ends as it said")
  ;; an unproved proof is no red: the judge's one run on the merged branch proves it (owner, 2026-11-16)
  (is (= :waiting (first (turn/hand-back-end nil {:reasons [{:reason :proof-unknown :symbol 'app.x-test/t}]}))) "only unproved proofs: handed to the judge")
  (is (= :red (first (turn/hand-back-end nil {:reasons [{:reason :proof-unknown :symbol 'app.x-test/t} {:reason :proof-red :symbol 'app.x-test/u}]})))
      "a recorded red beside them is red"))

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

;; Estimate: one call of seon.turn's end checks (decide) on a status of one red proof, with the agent's db, turn number, specified-tx and
;; model-request! stood in (no store read, no agent, no model call): the checks and a cond ~30k ticks, the output's maps ~100k = 100000;
;; bytes ~1 MB; ~1 ms (was a spawned agent and its eval round trip, 33,608 ms measured)
(deftest ^{:seon.test/estimate {:ticks 100000 :bytes 1000000}} a-turn-sends-its-model-call-and-returns
  ;; task seon.turn/a-turn-is-a-flow-step (docs/flow.md#the-step-fn-contract-every-proc-follows: a call out is an output, its answer a
  ;; message): a turn whose status asks the model (a task with a red proof) answers the model call's request as its output and returns:
  ;; the call is left pending on the run's state for its answer, and nothing is sent (no effect is out, no reply or error recorded).
  ;; The request's drawing and its sent hash are model-request!'s, proved with the recorded request by
  ;; seon.agent-test/a-failed-call-keeps-what-it-sent
  (let [old @seon.agent/state
        red {:reasons [{:reason :proof-red :symbol 'app.calc-test/total-sums}]}
        request {:messages [{:role "user" :content "page"}] :task "app.calc/total-is-exact" :call "k/2"}
        fx (atom 0)
        st {:ag {:key "k" :role :test} :task "app.calc/total-is-exact" :max-turns 3}]
    (try
      (with-redefs [seon.agent/db (constantly nil)
                    seon.agent/turn-now (constantly 1)
                    seon.agent/fx! (fn [& _] (swap! fx inc) {})
                    seon.turn/specified-tx (fn [& _] nil)
                    seon.turn/model-request! (fn [& _] {:id "k/2" :request request})]
        (reset! seon.agent/state (assoc old :turn {}))
        (let [[st' out :as r] (@#'seon.turn/decide st {} red)]
          (is (= #{:model} (set (keys out))) (str "the model call is the output: " (pr-str r)))
          (is (= request (:model out)) "its request drawn, as the output")
          (is (= "k/2" (get-in st' [:pending :id])) "and left pending on the run's state, for its answer")
          (is (= (assoc st :pending (:pending st')) st') "the run's state is otherwise as it was")
          (is (zero? @fx) "no effect is out: nothing was sent")))
      (finally (reset! seon.agent/state old)))))

;; Estimate: the task and agent on the world (~0.5M) and one recorded form calling turn-merged, whose throw path writes one message on the
;; agent's db (~0.5M) = ~1.5M; 2,000,000 ticks. Bytes: eval-in's task/agent write and the message each check the write against the world's
;; entity index (seon.db/entity-index, ~36 MB a write, measured 72 MB for the two), which the 2 MB first stated missed: 75,000,000. No root: my.test/eval-in
(deftest ^{:seon.test/estimate {:ticks 2000000 :bytes 75000000}} a-merge-that-answers-an-error-is-the-turns-throw-never-green
  ;; docs/merging.md#a-branch-merges-main-in: the writer answers a branch whose merge base was swept with its typed error, merging
  ;; nothing; after a green status that read as "the merge brought nothing" and ended the run :green, unjudged
  ;; the one thing a fork has no store for is the commit of the message turn-error! writes (seon.agent/commit!), stood in; the write is the fork's db
  (let [[r] (t/eval-in [(fn [_]
                          (with-redefs [agent/commit! (constantly nil)]
                            (let [st {:ag {:key "test-agent" :role :test} :task "app.calc/total-is-exact" :turn 0 :max-turns 3 :pending {:merged :green :status {}}}
                                  err (seon.contract/error :seon.db/no-merge-base 'seon.db/branch-base "no merge base: swept" {:branch :swept :commit nil})
                                  [k st'] (turn/turn-merged st err)]
                              {:k k :outcome (when (= :seon.turn/end k) (nth (turn/turn-merged st err) 2)) :thrown (:thrown st')})))]
                        'user {:db (t/world)})
        v (:value r)]
    (is (not= :green (:outcome v)) (str "a merge that answered an error never ends the run green: " (pr-str r)))
    (is (= 1 (:thrown v)) (str "it is the turn's throw, counted and the agent's to read: " (pr-str r)))))

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

(defn- held-run
  "A run of the world's task (seon.agent-test's work-with: its turns' effects stood in) driven by the real :repl proc (seon.repl/agent-step)
  in a graph, its status by status, with the effect var held (agent/fx!, the model call, or agent/judge!, the proofs) parked until the
  test is done: what (probe g) answers while the wait is out, then root's :stop, the run's reply (its end) and whether the process exited."
  ([status held probe] (held-run status held probe (fn [arg] (str "(seon.turn/work! agent " (pr-str arg) ")"))))
  ([status held probe form]
  (@#'at/work-with (fn [st called!] (called!) st) status
                (fn [ag arg]
                  (let [out (promise) release (promise) replied (promise) exited (promise)]
                    (with-redefs-fn {held (fn [& _] (deliver out true) @release {:value {}})
                                     #'agent/send! (fn [m] (when (and (= :reply (:op m)) (= 7 (:id m))) (deliver replied m)) true)
                                     #'lib/c-exit (fn [code] (deliver exited code) nil)}
                      (fn []
                        (let [g (flow/create-flow {:procs {:repl {:proc (flow/process #'repl/agent-step) :args {:ag ag}}}})]
                          (flow/start g) (flow/resume g)
                          ;; the graph is stopped inside the redefinitions whatever ends the test: a :stop it still holds must
                          ;; never reach the real lib/c-exit, which would end this test process (it ended a shard at exit 0 once)
                          (try
                            @(flow/inject g [:repl :in] [{:op :eval :id 7 :forms (form arg)}])
                            @out
                            (let [seen (probe g)]
                              @(flow/inject g [:repl :in] [{:op :stop}])
                              (let [reply @replied code @exited]
                                {:seen seen :end (get-in reply [:response :value]) :exit code}))
                            ;; the dropped call's fiber ends; its answer finds no run
                            (finally (deliver release true) (flow/stop g)))))))))))

(defn- ping-of
  "The :repl proc's site and ping answer in graph g. See docs/flow.md#flow-inside-an-agent."
  [g]
  (let [p (flow/ping g)] {:site (get-in p [:repl :clojure.core.async.flow/site]) :run (get-in p [:repl :clojure.core.async.flow/state :run])}))

;; Estimate: the world (~3.2M), one eval of work! through the real :repl proc (an eval's record ~0.5M), the run's first turn (~0.2M), a graph
;; started and stopped and a ping (~0.2M): ~4.1M; 8,000,000. Bytes: the world's seek and the turn's facts, ~4 MB; 8,000,000. Seconds: in
;; process, ~0.1 s
(deftest ^{:seon.test/estimate {:ticks 8000000 :bytes 8000000}} a-run-answers-ping-and-stop-between-turns
  ;; task seon.turn/a-turn-is-a-flow-step (docs/flow.md#flow-inside-an-agent): the :repl proc holds a run (work! in an eval) as its state and
  ;; takes each wait's answer as a message, so while the model call is out it is parked on its ports and answers a ping with the run; root's
  ;; :stop ends the run at once, :waiting, the call in flight dropped, replies to root's work! eval with that end, then exits. Red before: work!
  ;; drove the run inside one :repl step, stepping for the whole call, so no pong came and a stop waited for the turn's end
  (let [r (held-run (constantly {:reasons [{:reason :proof-red}]}) #'agent/fx! ping-of)]
    (is (not (contains? (get-in r [:seen :site]) :stepping)) (str "parked on its ports while the model call is out: " (pr-str (:seen r))))
    (is (= "app.calc/total-is-exact" (get-in r [:seen :run :task])) (str "the ping is answered with the run: " (pr-str (:seen r))))
    (is (= :waiting (get-in r [:end :outcome])) (str "the stop ended the run at once: " (pr-str (:end r))))
    (is (= 0 (get-in r [:end :model-calls])) "the call in flight was dropped, never waited for")
    (is (= 0 (:exit r)) "and the process exits after it")))

;; Estimate: as a-run-answers-ping-and-stop-between-turns, the proofs' wait in the model call's place: 8,000,000 ticks, 8,000,000 bytes
(deftest ^{:seon.test/estimate {:ticks 8000000 :bytes 8000000}} an-agent-answers-ping-while-its-proofs-are-queued
  ;; task seon.turn/a-turn-is-a-flow-step: a clear status sends the proofs (turn-begin's :prove, seon.agent/judge!) as an output; while the test
  ;; service holds them the agent sits at the turn boundary, parked, answering ping, so the service's back-pressure reaches the model spend
  ;; with no mechanism of its own. Red before: judge! waited inside the :repl step
  (let [r (held-run (constantly {:reasons []}) #'agent/judge! ping-of)]
    (is (not (contains? (get-in r [:seen :site]) :stepping)) (str "parked while its proofs are queued: " (pr-str (:seen r))))
    (is (= "app.calc/total-is-exact" (get-in r [:seen :run :task])) (str "the ping is answered with the run: " (pr-str (:seen r))))
    (is (= :waiting (get-in r [:end :outcome])) (pr-str (:end r)))))

;; Estimate: as an-agent-answers-ping-during-its-hand-back, main's merge held in the judge's place: 8,000,000 ticks, 8,000,000 bytes
(deftest ^{:seon.test/estimate {:ticks 8000000 :bytes 8000000}} a-run-answers-ping-while-main-merges
  ;; task seon.turn/no-turn-step-waits-for-main-or-its-observation (docs/flow.md#flow-inside-an-agent): main's merge into the branch is an
  ;; output ({:merge}) answered as a message, so while root's writer merges, the :repl proc is parked and answers a ping. Red before:
  ;; hand-back-step and turn-begin called my.branch/merge-main! inside the step
  (let [r (held-run (constantly {:reasons []}) #'my.branch/merge-main! ping-of (fn [arg] (str "(seon.turn/hand-back! agent " (pr-str (:task arg)) ")")))]
    (is (not (contains? (get-in r [:seen :site]) :stepping)) (str "parked while main merges: " (pr-str (:seen r))))
    (is (= "app.calc/total-is-exact" (get-in r [:seen :run :task])) (str "the ping is answered with the hand-back: " (pr-str (:seen r))))
    (is (= :waiting (get-in r [:end :outcome])) (str "the stop ended it at once: " (pr-str (:end r))))))

;; Estimate: as an-agent-answers-ping-while-its-proofs-are-queued, the observation held: 8,000,000 ticks, 8,000,000 bytes
(deftest ^{:seon.test/estimate {:ticks 8000000 :bytes 8000000}} a-run-answers-ping-while-its-observation-is-planned
  ;; task seon.turn/no-turn-step-waits-for-main-or-its-observation: a turn's observation (seon.turn/observe: its proofs run by the test
  ;; service) is an output ({:observe}), so while the service plans them the :repl proc is parked and answers a ping. Red before: turn-begin
  ;; observed inside the step
  (let [r (held-run (constantly {:reasons [{:reason :proof-red}]}) #'turn/observe ping-of)]
    (is (not (contains? (get-in r [:seen :site]) :stepping)) (str "parked while its observation is planned: " (pr-str (:seen r))))
    (is (= "app.calc/total-is-exact" (get-in r [:seen :run :task])) (str "the ping is answered with the run: " (pr-str (:seen r))))
    (is (= :waiting (get-in r [:end :outcome])) (pr-str (:end r)))))

;; Estimate: two calls of status-now with its readers stood in (no store read, no render): ~0.1M ticks, ~1 MB, ~1 ms
(deftest ^{:seon.test/estimate {:ticks 100000 :bytes 1000000}} a-cold-process-reads-its-status-without-drawing-its-page
  ;; Stage 0's 20G (2026-11-15): a hand-back in a re-attached process asked its status of the page walk, which drew the whole conversation
  ;; cold (a page diff each turn, each over a collected commit's as-of view). With no walk held the status is read directly; a held walk
  ;; still gives its kept status, so a warm turn computes it once (docs/turn.md#act)
  (let [old @seon.agent/state
        ag {:key "k" :role :test}
        drawn (volatile! 0) direct (volatile! 0)]
    (try
      (with-redefs [seon.agent/db (constantly nil)
                    seon.repl/page-walk (fn [_] (vswap! drawn inc) {:held true})
                    seon.render/kept-of (fn [_ _] {:reasons [] :from :walk})
                    seon.ctx.status/status (fn [_ _ _] (vswap! direct inc) {:reasons [] :from :direct})]
        (reset! seon.agent/state (dissoc old :walk))
        (let [s (@#'turn/status-now ag)] (is (= :direct (:from s)) (str "no walk held: the status is read directly: " (pr-str s))))
        (is (zero? @drawn) "and the page is not drawn")
        (swap! seon.agent/state assoc :walk {:held true})
        (is (= :walk (:from (@#'turn/status-now ag))) "a held walk: its kept status")
        (is (= 1 @drawn) "advanced once, as a warm turn does"))
      (finally (reset! seon.agent/state old)))))

;; Estimate: as an-agent-answers-ping-while-its-proofs-are-queued, a hand-back's begin and status in a run's place: 8,000,000 ticks, 8,000,000 bytes
(deftest ^{:seon.test/estimate {:ticks 8000000 :bytes 8000000}} an-agent-answers-ping-during-its-hand-back
  ;; seon.step/every-request-is-answered-at-each-end-of-its-holder's first part, root's order item 9 (docs/flow.md#flow-inside-an-agent): an
  ;; outside agent's hand-back (seon.turn/hand-back!, seon_done's path) is a run on the same protocol, so while its observation is out the
  ;; :repl proc is parked, answers a ping with it, and root's :stop ends it at once. Red before: hand-back! awaited agent/judge! inside the
  ;; :repl step; it proves nothing now (its judge does), so it waits on its merge and its observation, each an output
  (let [r (held-run (constantly {:reasons []}) #'turn/observe ping-of (fn [arg] (str "(seon.turn/hand-back! agent " (pr-str (:task arg)) ")")))]
    (is (not (contains? (get-in r [:seen :site]) :stepping)) (str "parked while its observation is out: " (pr-str (:seen r))))
    (is (= "app.calc/total-is-exact" (get-in r [:seen :run :task])) (str "the ping is answered with the hand-back: " (pr-str (:seen r))))
    (is (= :waiting (get-in r [:end :outcome])) (str "the stop ended it at once: " (pr-str (:end r))))))

;; task seon.prove/a-test-that-dies-fails-only-itself: a run that failed as a whole is held on the agent whole, its own keys
;; with it (a no-verdict's :test and :reported say what the run did report). Red before: only its type, operation and message
;; were kept. Estimate: one seek of the agent and one transaction handed to a stand-in, ~20k ticks.
(deftest ^{:seon.test/estimate {:ticks 100000}} a-failed-runs-error-is-held-whole
  (let [old @seon.agent/state
        db (t/with (t/world) [{:seon.agent/key "k-err"}])
        err (seon.contract/error :seon.prove/no-verdict 'seon.prove/batch-ended "app.x-test/t's batch answered no verdict for it"
                                 {:test 'app.x-test/t :digest "0123456789abcdef" :reported ['app.x-test/u]})
        got (atom nil)]
    (try
      (reset! seon.agent/state (assoc old :key "k-err"))
      (with-redefs [seon.agent/db (constantly db) seon.agent/transact! (fn [_ tx] (reset! got tx) {})]
        (@#'seon.turn/test-error! {:key "k-err" :role :test} err))
      (is (= ['app.x-test/u] (get-in (first @got) [:seon.agent/test-error :reported])) (pr-str @got))
      (finally (reset! seon.agent/state old)))))
