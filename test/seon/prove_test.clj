(ns seon.prove-test
  "The test service (seon.prove): admission as a step-fn (one run per checkout, the latest request
  of a branch wins, a running key is joined, every waiter answered once); a run writes nothing to the
  store (its agent caches its verdicts by digest: seon.results-test).
  Run: bin/test seon.prove-test"
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d]
            [seon.test-store :as ts] [clojure.java.shell :as sh] [seon.agent-test :as at] [clojure.edn] [seon.prove :as prove] [seon.fx :as fx] [seon.step :as step] [seon.agent] [seon.cost] [seon.proofs :as proofs] [seon.program] [seon.config] [seon.reconcile]))

;; the service's state as a test starts it: the max-workers of the config in force (seon.fx/limits), which deals the queue in
;; shares; nothing here reads the machine's load (docs/cost.md#what-is-measured: no assertion the load can turn red)
(defn- proc0 [] (prove/prove-proc {:conn nil}))

(defn- feed
  "State st after the messages ([in m] ...) in order, with every output it made (the :to-api messages)."
  [st msgs]
  (reduce (fn [[st outs] [in m]] (let [[st o] (prove/prove-proc st in m)] [st (into outs (:to-api o))])) [st []] msgs))

(defn- req
  "A request for branch at tx t, the digest of what it reads moving with t (a change at each tx)."
  [rid branch t & [agent]] [:req (cond-> {:rid rid :branch branch :t t :digest (str "d" t)} agent (assoc :agent agent))])
(defn- unwrap
  "A test batch the service asked of the :process owner, read as the :tests ask of the agent it carries."
  [m] (if (= :test (get-in m [:req :req :kind])) (assoc m :req (get-in m [:req :req :seon.fx/ask])) m))
(defn- asks [outs op] (filterv #(= op (get-in % [:req :op])) (map unwrap outs)))
(defn- answer-of [m r] [:answers {:tag (:tag m) :r r}])
(defn- waiters-answered [outs] (sort (map :rid (filter #(= :prove-answer (:op %)) outs))))

(def ^:private test-run-answer {:pass 1 :fail 0 :error 0 :reports [{:type :pass :var "#'a.b-test/t"}] :digests {'a.b-test/t "d1"} :ms 5})

(defn- run-through
  "Drive the run that st (a run just started by `outs`) is on, to its end: the runner answers a plan request with
  answer's tests as its items (at its digests), each item's tests request with answer, the write with ok. Answers
  [st all-outputs]."
  [st outs answer]
  (loop [st st all outs seen 0]
    (let [pending (drop seen all)]
      (if-let [m (first (filter :req pending))]
        (let [op (get-in (unwrap m) [:req :op])
              ;; the service plans a run (its agent's :tests with :plan?) before it asks for each item by name
              plan (if (or (seon.contract/error? answer) (not (map? answer))) answer
                     {:items (:digests answer) :selected (vec (keys (:digests answer)))})
              r (case op :spawn {:id :p1} :tests (if (get-in (unwrap m) [:req :plan?]) plan answer) :write {:ok true :t 6} :kill {:killed :p1})
              [st o] (feed st [(answer-of m r)])]
          (recur st (into all o) (inc (.indexOf all m))))
        [st all]))))

;; docs/merging.md#the-test-service: a judge's named ask on main while main's own run holds the tests branch waits for that runner,
;; never a second spawn the branch refuses (data, 2026-11-16, result/bbd0ab17: every pilot judged during a main run read its reds
;; :unknown from :seon.system/refused). Estimate: a few messages through the step fn, ~20k ticks each = ~0.3M.
(defn- named-ask [rid t] [:req {:rid rid :branch :main :t t :digest (str "d" t) :names #{'a.b-test/t} :mode :judge}])

(deftest ^{:seon.test/estimate {:ticks 500000}} two-asks-held-on-the-tests-branch-are-both-answered
  (let [[st o] (feed (proc0) [(req 1 :main 5) (named-ask 2 5) (named-ask 3 6)])
        _ (is (= 1 (count (asks o :spawn))) "one runner on the tests branch; the two asks wait for it, never a refused spawn")
        [st all] (run-through st o test-run-answer)]
    (is (= [1 2 3] (waiters-answered all)) "each asker answered, in turn")
    (is (every? #(= 1 (count %)) (vals (group-by :tag (asks all :spawn)))) "one spawn at a time")
    (is (empty? (:held-main st)) "nothing left held")))

(deftest ^{:seon.test/estimate {:ticks 500000}} a-named-ask-during-mains-run-is-answered-with-its-verdicts
  (let [[st o] (feed (proc0) [(req 1 :main 5) (named-ask 2 5)])
        _ (is (= 1 (count (asks o :spawn))) "while main's runner is in flight the ask asks no second runner, which the branch would refuse")
        [_ all] (run-through st o test-run-answer)
        r (:r (first (filter #(and (= :prove-answer (:op %)) (= 2 (:rid %))) all)))]
    (is (not (seon.contract/error? r)) (str "answered, never refused: " (pr-str r)))
    (is (= {'a.b-test/t :pass} (proofs/results r)) "with its test's verdict")))

(deftest ^{:seon.test/estimate {:ticks 500000}} a-dying-main-run-answers-its-waiters-and-the-held-ask-runs
  (let [[st o] (feed (proc0) [(req 1 :main 5) (named-ask 2 5)])
        died (seon.contract/error :seon.system/refused 'seon.system/spawn! "the fork server could not fork it" {})
        [st o2] (feed st [(answer-of (first (asks o :spawn)) died)])]
    (is (= [1] (waiters-answered o2)) "the dying run answers its own waiter")
    (is (seon.contract/error? (:r (first (filter #(= :prove-answer (:op %)) o2)))) "with its typed error")
    (is (= 1 (count (asks o2 :spawn))) "and the held ask spawns its own runner")
    (is (empty? (:held-main st)))))

(deftest ^{:seon.test/estimate {:ticks 500000}} a-running-key-is-joined-and-every-waiter-is-answered-once
  (let [[st outs] (feed (proc0) [(req 1 :main 5) (req 2 :main 5) (req 3 :main 5)])]
    (is (= 1 (count (asks outs :spawn))) "three askers of one key: one run")
    (let [[st all] (run-through st outs test-run-answer)]
      (is (= [1 2 3] (waiters-answered all)) "each answered, once")
      (is (= [true nil] (mapv #(get-in % [:req :plan?]) (asks all :tests))) "one plan and one run of its one item")
      (is (= {} (:runs st)) "and the run has left the service")
      (testing "the key again after its run: answered at once, nothing runs"
        (let [[_ o] (feed st [(req 4 :main 5)])]
          (is (= [4] (waiters-answered o)))
          (is (empty? (filter :req o))))))))

(deftest ^{:seon.test/estimate {:ticks 500000}} an-answer-whose-child-died-is-never-remembered
  ;; THE PLAN 0.7 (root, 2026-10-27): a judge's run whose test child died answered every later ask
  ;; of its key from memory, so the held task's next run (seon_done again, same branch, same
  ;; program) got the same death and never ran its proof. A death is no verdict: the next ask runs
  (let [died {:pass 0 :fail 0 :error 1 :reports [{:type :error :var "#'a.b-test/t" :exit {:exit 1} :message "test process died"}] :digests {'a.b-test/t "d1"} :ms 5}
        [st outs] (feed (proc0) [(req 1 :main 5)])
        [st all] (run-through st outs died)
        _ (is (= [1] (waiters-answered all)) "its waiter is answered the death")
        [_ o] (feed st [(req 2 :main 5)])]
    (is (empty? (waiters-answered o)) "asked again: not answered from memory")
    (is (= 1 (count (asks o :spawn))) "it runs again")))

(deftest ^{:seon.test/estimate {:ticks 500000}} a-run-is-keyed-by-what-it-reads
  ;; seon.system/prove! and seon.agent/prove! name the digest of what the run reads: a branch's tx
  ;; moves with any write, the run's own facts, its prove agent's end and the eval that asked among them
  (let [main (fn [rid t digest] [:req {:rid rid :branch :main :t t :digest digest}])
        [st outs] (feed (proc0) [(main 1 5 "d0")])
        [st _] (run-through st outs test-run-answer)]
    (testing "main at a later tx, nothing it reads changed: the ended run's answer, nothing runs"
      (let [[_ o] (feed st [(main 2 9 "d0")])]
        (is (= [2] (waiters-answered o)))
        (is (empty? (filter :req o)))))
    (testing "a change to the code or the tests moves the digest: a new run"
      (let [[_ o] (feed st [(main 3 9 "d1")])]
        (is (empty? (waiters-answered o)))
        (is (= 1 (count (asks o :spawn))))))))

(deftest ^{:seon.test/estimate {:ticks 500000}} done-holds-only-what-can-be-asked-again
  ;; docs/merging.md#the-test-service: a remembered answer whose key holds a digest the branch has moved past is never
  ;; matched again, so the service keeps a branch's slots at its latest digest only, and none once its agent ended
  (let [ask (fn [st rid t names] (let [[st o] (feed st [[:req (cond-> {:rid rid :branch :agent-3 :t t :digest (str "d" t) :agent :a3} names (assoc :names names))]])]
                                   (first (run-through st o test-run-answer))))
        st (-> (proc0) (ask 1 5 nil) (ask 2 5 '#{a.b-test}))]
    (is (= 2 (count (:done st))) "two slots at one digest: both can be asked again")
    (let [st (ask st 3 6 nil)]
      (is (= [[:agent-3 nil :judge]] (keys (:done st))) "the branch moved to d6: the slots at d5 are dropped")
      (let [ended (seon.contract/error :seon.system/ended 'seon.system/api-proc "agent a3 ended" {:agent :a3})
            [st o] (feed st [(req 4 :agent-3 7 :a3)])
            [st _] (run-through st o ended)]
        (is (empty? (:done st)) "its agent ended: nothing of the branch is kept")))))

(deftest ^{:seon.test/estimate {:ticks 500000}} a-session-and-an-agents-turn-for-one-digest-are-one-run
  ;; the session's seon_prove for agent a3's branch at tx 7, and a3's turn asking for the same
  (let [[st outs] (feed (proc0) [(req 10 :agent-3 7 :a3) (req 11 :agent-3 7 :a3)])]
    (is (= 1 (count (asks outs :tests))) "one plan request, to the agent that holds the branch")
    (is (= {:op :tests :id :a3 :mode :judge :plan? true :key "agent-3@d7" :digest "d7"} (:req (first (asks outs :tests)))) "its run's key and digest, which the agent holds the plan under")
    (is (empty? (asks outs :spawn)) "the agent runs its own branch: no prove agent")
    (let [[_ all] (run-through st outs test-run-answer)]
      (is (= [10 11] (waiters-answered all)))
      (is (= [true nil] (mapv #(get-in % [:req :plan?]) (asks all :tests))) "one plan and one run of its item, for both"))))

(deftest ^{:seon.test/estimate {:ticks 200000}} a-failed-run-answers-its-waiters-with-the-error
  (let [[st outs] (feed (proc0) [(req 1 :a 1 :aa) (req 2 :a 1 :aa)])
        err (seon.contract/error :seon.system/no-agent 'seon.system/run-tests! "no live agent" {})
        [st all] (run-through st outs err)]
    (is (= [1 2] (waiters-answered all)))
    (is (every? seon.contract/error? (map :r (filter #(= :prove-answer (:op %)) all))))))


;; docs/merging.md#verdicts-are-a-cache: the daily run is what catches what a digest can't see, so what it finds is told, not only cached
(deftest ^{:seon.test/estimate {:ticks 200000}} a-red-in-the-full-run-is-a-problem
  (let [request [:req {:branch :main :digest "full-2026-11-07" :mode :full}]
        [st outs] (feed (proc0) [request])
        fx (first (filter #(= :fx (get-in % [:req :op])) outs))
        told (fn [answer] (let [[st2 o] (feed st [(answer-of fx answer)])] [st2 (filter #(= :problem (:op %)) o)]))
        [st-r reds] (told {:value {:results '{a/green :pass a/red :fail a/died :error}}})
        [_ none] (told {:value {:results '{a/green :pass}}})]
    (is (= [:seon.prove/full-run-red] (map #(get-in % [:error :seon.error/type]) reds)))
    (is (= '[a/died a/red] (get-in (first reds) [:error :tests])) "it names each, and a green is none of them")
    (is (nil? (:full st-r)) "the run has ended")
    (is (empty? none) "all green tells nothing")))

;; ---- the daily full run (the cron proc's request, :full mode) -----------------------------------

(def ^:private full-answer
  {:value {:results '{a.b-test/t :pass a.c-test/u :fail} :digests '{a.b-test/t "dg-t" a.c-test/u "dg-u"} :ms 9 :log "/x.log"
           :failures '{a.c-test/u [{:type :fail :message "boom" :expected "1" :actual "2"}]}}})

(deftest ^{:seon.test/estimate {:ticks 500000}} the-full-run-is-a-run-of-the-tests-effect-recorded-on-main
  ;; root, 2026-10-27: the narrow digests are safe because a daily full run catches the rest. The
  ;; cron proc's request (no asker) runs apart (a full run takes most of an hour and
  ;; can stall: an agent's turn never waits on it); its executor is the :tests effect (bin/test --all
  ;; in a worktree of main, an OS child), whose shards cache their verdicts as any run does
  (let [request [:req {:branch :main :digest "full-2026-10-28" :mode :full}]
        [st outs] (feed (proc0) [request])
        fx (first (filter #(= :fx (get-in % [:req :op])) outs))]
    (is (= "main@full-2026-10-28!full" (prove/run-key :main "full-2026-10-28" nil :full)))
    (is (empty? (asks outs :spawn)) "no prove agent is spawned")
    (is (= {:op :fx :kind :tests :req {:all? true}} (:req fx)) "the effect runs every test")
    (testing "an agent's run while the full run runs starts at once: the run slot is not held"
      (let [[_ o] (feed st [(req 1 :agent-1 5 :a1)])]
        (is (= 1 (count (asks o :tests))))))
    (testing "another day's request while it runs is a :platform problem naming the run that has not ended"
      (let [[_ o] (feed st [[:req {:branch :main :digest "full-2026-10-29" :mode :full}]])]
        (is (empty? (filter :req o)))
        (is (= [:seon.prove/full-run-running] (map #(get-in % [:error :seon.error/type]) (filter #(= :problem (:op %)) o))))))
    (testing "the same day's request while it runs joins it; the effect answered, the full run is free and no fact is written for the verdicts"
      (let [[st2 o2] (feed st [request])
            [st3 o3] (feed st2 [(answer-of fx full-answer)])]
        (is (empty? (filter :req o2)) "no second run")
        (is (nil? (:full st3)) "the full run is free")
        (is (empty? (filter :req o3)) "no write: its shards cached their verdicts")
        (is (= ['a.c-test/u] (mapcat #(get-in % [:error :tests]) (filter #(= :problem (:op %)) o3))) "a red it found is told: a-red-in-the-full-run-is-a-problem")
        (testing "the day again: answered by its run, nothing runs"
          (is (empty? (filter :req (second (feed st3 [request]))))))))
    (testing "an effect that failed is a platform problem, and the full run is free for the next day"
      (let [err (seon.contract/error :seon.fx/io-error #'seon.prove/prove-proc "bin/worktree --at exited 1" {})
            [st5 o5] (feed st [(answer-of fx err)])]
        (is (nil? (:full st5)))
        (is (= 1 (count (filter #(= :problem (:op %)) o5))))))))

;; ---- the proof root asks for: one run for a session and an agent's turn ----------------------

(deftest ^{:seon.test/estimate {:ticks 200000}} an-agents-run-leaves-every-process-test-to-the-judge
  ;; root, 2026-10-27 (THE PLAN 0.7's first run): a child forked from an agent has no process
  ;; store, so a process test an agent ran there errored, named or a proof. It is left out and listed
  ;; whatever the request names, and runs at the judge's merge gate (bin/test, on a checkout, still
  ;; runs a named one: seon.proofs/left-out)
  (let [plan {:selected '[a.b-test/plain a.p-test/proc a.p-test/other] :run '[a.b-test/plain a.p-test/proc a.p-test/other]
              :process '#{a.p-test/proc}}
        sel (fn [names proofs] (let [r (seon.agent/run-selection plan names proofs)] (select-keys r [:selected :run :skipped])))]
    (testing "naming nothing: the process test is skipped, and listed"
      (is (= '{:selected [a.b-test/plain a.p-test/other] :run [a.b-test/plain a.p-test/other] :skipped [a.p-test/proc]} (sel nil #{}))))
    (testing "naming its namespace: still skipped, and listed"
      (is (= '{:selected [a.p-test/other] :run [a.p-test/other] :skipped [a.p-test/proc]} (sel '#{a.p-test} #{}))))
    (testing "naming another namespace: it is not in the run"
      (is (= '{:selected [a.b-test/plain] :run [a.b-test/plain] :skipped []} (sel '#{a.b-test} #{}))))
    (testing "a task's proof that reaches a process: still the judge's"
      (is (= '[a.b-test/plain a.p-test/other] (:run (sel nil '#{a.p-test/proc}))))
      (is (= '[a.p-test/proc] (:skipped (sel nil '#{a.p-test/proc})))))
    (testing "bin/test's plan on a checkout runs a named or proof process test"
      (is (= #{} (proofs/left-out '[a.p-test/proc] '#{a.p-test/proc} true #{})))
      (is (= #{} (proofs/left-out '[a.p-test/proc] '#{a.p-test/proc} false '#{a.p-test/proc}))))))

;; ---- admission details ----------------------------------------------------------------------

(deftest ^{:seon.test/estimate {:ticks 500000}} an-error-is-answered-recorded-and-never-remembered
  (let [err (seon.contract/error :seon.system/no-agent 'seon.system/run-tests! "no live agent" {})
        [st outs] (feed (proc0) [(req 1 :agent-3 7 :a3)])
        [st all] (run-through st outs err)]
    (is (= [1] (waiters-answered all)))
    (is (some #(and (= :problem (:op %)) (= :platform (:class %))) all) "recorded as a :platform problem, as well as answered")
    (is (empty? (:done st)) "nothing remembered")
    (testing "asking again runs again"
      (let [[st outs] (feed st [(req 2 :agent-3 7 :a3)])
            [_ all] (run-through st outs test-run-answer)]
        (is (= 1 (count (asks outs :tests))))
        (is (= [2] (waiters-answered all)))
        (is (not (seon.contract/error? (:r (first (filter #(= :prove-answer (:op %)) all))))))))))

(deftest ^{:seon.test/estimate {:ticks 200000}} a-run-whose-agent-ended-is-answered-not-a-platform-problem
  ;; bin/check e2e-calc (2026-10-23): a cancel killed an agent whose turn had asked prove for its
  ;; tests; root answers a request to an agent that ended :seon.system/ended (its producer knows
  ;; ended from never-was), the run's waiter is answered with it, and nothing is recorded as a
  ;; :platform problem: the agent's end is its run's record
  (let [ended (seon.contract/error :seon.system/ended 'seon.system/api-proc "agent a3 ended" {:agent :a3})
        [st outs] (feed (proc0) [(req 1 :agent-3 7 :a3)])
        [st all] (run-through st outs ended)]
    (is (= [1] (waiters-answered all)) "its waiter is answered with the error")
    (is (not-any? #(= :problem (:op %)) all) "and no :platform problem is recorded")
    (is (empty? (:done st)) "nothing remembered")))

(deftest ^{:seon.test/estimate {:ticks 500000}} a-spawn-failure-and-a-prove-agent-that-dies-are-answered-and-recorded
  (let [[st outs] (feed (proc0) [(req 1 :main 1) (req 2 :main 1)])
        spawn (first (asks outs :spawn))
        refused (seon.contract/error :seon.system/refused 'seon.system/spawn! "too many live agents" {})
        [st o] (feed st [(answer-of spawn refused)])]
    (is (= [1 2] (waiters-answered o)) "a spawn refused: both waiters answered the error")
    (is (some #(= :problem (:op %)) o))
    (is (nil? (:running st))))
  (let [[st outs] (feed (proc0) [(req 1 :main 1)])
        [st o2] (feed st [(answer-of (first (asks outs :spawn)) {:id :p1})])
        died (seon.contract/error :seon.agent/died 'seon.system/api-proc "agent p1 died" {:agent :p1})
        [st o3] (feed st [(answer-of (first (asks o2 :tests)) died)])]
    (is (= [1] (waiters-answered o3)) "the prove agent died mid-run: its waiter is answered the error")
    (is (some #(and (= :problem (:op %)) (= :seon.agent/died (get-in % [:error :seon.error/type]))) o3))
    (is (= 1 (count (asks o3 :kill))) "and its agent is killed")
    (is (empty? (:done st)))))

(deftest ^{:seon.test/estimate {:ticks 200000}} a-stale-answer-is-counted
  (let [[st outs] (feed (proc0) [(req 1 :main 1)])
        [st _] (feed st [[:answers {:tag 99 :r {}}]])]
    (is (= 1 (:stale st)))
    (is (= 1 (:stale-answers ((:ping-map-fn (prove/prove-proc)) st))))))

(defn- lcg
  "The next of a linear congruential sequence from x (a pure function: the test draws no randomness)."
  [x] (mod (+ (* x 6364136223846793005) 1442695040888963407) 9223372036854775807))

(defn- model-run
  "A service driven through steps pseudo-random events from seed: a request (a branch of main, b1
  or b2, a digest of three, an asker agent for a branch) or the api answering one of the asks still
  open (a success or an :seon.system/ended error, as an agent ending answers). Then every ask left is
  answered with a success, until none. Answers [final-state rids-asked answers-seen]."
  [seed steps]
  (loop [i 0 x seed st (proc0) open [] asked [] answers []]
    (let [x (lcg x) pick (quot (mod x 1000003) 7)]
      (if (< i steps)
        (if (or (empty? open) (even? (quot (mod x 100003) 3)))
          ;; a request
          (let [rid (inc (count asked)) branch ([:main :b1 :b2] (mod pick 3)) t (inc (mod (quot pick 3) 3))
                [st o] (feed st [(req rid branch t (when-not (= :main branch) ({:b1 :a1 :b2 :a2} branch)))])]
            (recur (inc i) x st (into open (filter :req o)) (conj asked rid) (into answers (waiters-answered o))))
          ;; the api answers one open ask
          (let [m (open (mod pick (count open))) op (get-in (unwrap m) [:req :op])
                r (if (zero? (mod (quot pick 5) 3))
                    (seon.contract/error :seon.system/ended #'seon.prove/prove-proc "the agent ended" {:agent :a1})
                    (case op :spawn {:id :p1} :tests test-run-answer :write {:ok true :t 6} :kill {:killed :p1}))
                [st o] (feed st [(answer-of m r)])]
            (recur (inc i) x st (into (vec (remove #(= m %) open)) (filter :req o)) asked (into answers (waiters-answered o)))))
        ;; drain: the api answers what is left with successes
        (loop [st st open open answers answers n 0]
          (if (and (seq open) (< n 200))
            (let [m (first open) op (get-in (unwrap m) [:req :op])
                  [st o] (feed st [(answer-of m (case op :spawn {:id :p1} :tests test-run-answer :write {:ok true :t 6} :kill {:killed :p1}))])]
              (recur st (into (subvec open 1) (filter :req o)) (into answers (waiters-answered o)) (inc n)))
            [st asked answers]))))))

;; est: 25 seeds x ~25 feeds (14 steps + drain) x ~3k ticks (measured 72k ticks a seed) = ~1.8M ticks, ~15 ms; 300 seeds cost 207 ms
(deftest ^{:seon.test/estimate {:ticks 3000000}} every-request-is-answered-once-whatever-order-the-api-answers-in
  ;; a Claude session's or an agent's turn waits on the service for ever if one request it took is
  ;; never answered (three hangs: an agent parked in seon.agent/prove! for the answer to its :prove).
  ;; 25 interleavings of requests and answers, errors among them: each request answered exactly once
  ;; and the service idle at the end
  (doseq [seed (range 1 26)]
    (let [[st asked answers] (model-run seed 14)]
      (is (= (sort asked) (sort answers)) (str "seed " seed ": each request answered, once: asked " (vec asked) " answered " (vec (sort answers))))
      (is (and (nil? (:running st)) (empty? (:next st))) (str "seed " seed ": the service ends idle: " (pr-str (select-keys st [:running :next])))))))

(defn- items-st [budget] (assoc (proc0) :fixed-budget budget))
(defn- batches [outs] (asks outs :tests))
(defn- answers [outs] (into {} (for [o outs :when (= :asker-answered (:op o))] [(:asker o) (:r o)])))
(def ^:private green-of (fn [& ts] {:pass (count ts) :fail 0 :error 0 :reports (mapv #(hash-map :type :pass :var (str "#'" %)) ts)}))

;; docs/merging.md#the-test-service: a runner ends when its last item does. A runner still owning a running item when its run finished
;; was ended only by a later finish, so with none, it held the tests branch and every held main ask waited for good (task
;; seon.prove/a-runner-ends-when-its-last-item-does; found reading for pilot 14). Red before: the item's batch ended and no kill was asked.
;; Estimate: one batch through the step fn ~0.1M.
(deftest ^{:seon.test/estimate {:ticks 300000}} a-runner-ends-when-its-last-item-does
  (let [[st o1] (prove/ask-items (items-st 1) 7 :batch {['app.x-test/t "d1"] {:agent :p1 :ms 5}})
        b (first (batches o1))
        ;; the run that spawned runner :p1 has finished; its item still runs
        st (assoc st :runners {"k0" :p1})
        [st o2] (feed st [(answer-of b (green-of 'app.x-test/t))])]
    (is (= [:p1] (mapv #(get-in % [:req :id]) (asks o2 :kill))) "the item's end ends its runner")
    (is (contains? (:killing st) "k0") "asked once")))


;; docs/merging.md#the-test-service: ten askers of one item run it once, and each is answered when its last item is green
(deftest ^{:seon.test/estimate {:ticks 200000}} an-item-many-ask-runs-once-and-answers-each
  (let [it {:agent :a1 :ms 50}
        [st o1] (prove/ask-items (items-st 4) 1 :batch {['app.x-test/t "d1"] it})
        [st o2] (prove/ask-items st 2 :batch {['app.x-test/t "d1"] it})
        _ (is (= 1 (count (batches (into o1 o2)))) "the second asker joins the item: one batch, not two")
        _ (is (= {} (answers (into o1 o2))) "nobody is answered before it ends")
        [st m] (step/answered st (:tag (first (batches o1))))
        [st o3] (prove/batch-ended st (parse-long (second m)) (green-of 'app.x-test/t))]
    (is (= {1 true 2 true} (update-vals (answers o3) :green)) "both answered green from the one run")
    (is (= {} (:items st)) "and the item has left the service: its verdict is the cache's")))

;; docs/merging.md#verdicts-are-a-cache: a batch's verdicts (its agent's :seon.proof tx-data) are written by the prove proc, one write
;; a batch, never by the batch's agent; a results branch the store lacks is made from main, and the same write asked again.
;; Estimate: one item asked, its batch ended, three answers stepped: 300,000 ticks
(deftest ^{:seon.test/estimate {:ticks 300000}} a-batchs-verdicts-are-written-by-the-prove-proc-once-a-batch
  (let [vt [{:seon.proof/id "v1" :seon.proof/test 'app.x-test/t :seon.proof/status :pass}]
        writes (fn [outs op] (filter #(= op (get-in % [:req :req :op])) (filter #(= :write (get-in % [:req :op])) (map unwrap outs))))
        [st o1] (prove/ask-items (items-st 4) 1 :batch {['app.x-test/t "d1"] {:agent :a1 :ms 50}})
        [st m] (step/answered st (:tag (first (batches o1))))
        [st o2] (prove/batch-ended st (parse-long (second m)) (assoc (green-of 'app.x-test/t) :verdict-tx vt))
        [w] (writes o2 :tx)]
    (is (= vt (get-in w [:req :req :tx-data])) "the batch's verdicts, asked of the writer once")
    (is (= (seon.config/value :seon.config.proofs/results-branch) (get-in w [:req :req :branch])) "on the results branch")
    (let [[st k] (step/answered st (:tag w))
          [st o3] (#'prove/answered st k {:seon.error/type :seon.writer/refused :seon.error/message "Database does not exist."})
          [c] (writes o3 :clone-branch)
          _ (is (= :db (get-in c [:req :req :from])) "a results branch the store lacks is made from main")
          [st k2] (step/answered st (:tag c))
          [_ o4] (#'prove/answered st k2 {:ok true})]
      (is (= vt (get-in (first (writes o4 :tx)) [:req :req :tx-data])) "and the same write asked again"))))

;; an asker wants to know whether its tests pass: its first red answers it at once, and the work only it wanted is dropped
(deftest ^{:seon.test/estimate {:ticks 200000}} a-red-answers-its-asker-at-once-and-drops-what-only-it-wanted
  ;; a red asker no longer waits on the rest: an item asked of the owner already ends with no one to answer, and one still
  ;; queued (its tree's build pending) leaves the queue unasked
  (let [[st o1] (prove/ask-items (items-st 2) 1 :batch {['app.a-test/t "da"] {:agent :a1 :ms 10} ['app.b-test/u "db"] {:agent :a1 :ms 10}})
        [b1 b2] (batches o1)
        [st v] (step/answered st (:tag b1))
        red {:pass 0 :fail 1 :error 0 :reports [{:type :fail :var (str "#'" (first (get-in b1 [:req :names]))) :message "no"}]}
        [st o2] (prove/batch-ended st (parse-long (second v)) red)
        [st v2] (step/answered st (:tag b2))
        [st o3] (prove/batch-ended st (parse-long (second v2)) (green-of (first (get-in b2 [:req :names]))))]
    (is (= 2 (count (batches o1))) "both asked of the owner at once, a share each")
    (is (false? (:green (get (answers o2) 1))) "the first red answers its asker, red")
    (is (= {} (answers o3)) "the other ends answering no one: its asker was answered")
    (is (= {} (:items st)) "and has left the service")))

(deftest ^{:seon.test/estimate {:ticks 500000}} every-proof-has-its-verdict-though-one-goes-red
  ;; Sean, 2026-11-11 ("have them fix that"): an agent saw only its first red, a proof each turn. Past a red the asker keeps
  ;; waiting for its own proofs and is answered at the last, every red named; what is no proof of its own it stops waiting on
  (let [p (fn [n] [(symbol "app.p-test" n) (str "d" n)])
        items (into {(p "x") {:agent :a1 :ms 10}} (for [n ["one" "two" "three"]] [(p n) {:agent :a1 :ms 10}]))
        [st o] (prove/ask-items (items-st 1) 1 :batch items '#{app.p-test/one app.p-test/two app.p-test/three})
        report (fn [t] (if (#{"two" "x"} (name t)) {:type :pass :var (str "#'" t)} {:type :fail :var (str "#'" t) :message "no"}))
        ;; end each granted batch in turn, a verdict for each of its tests: red for one and three, green for two and x
        run (fn [st o]
              (loop [st st bs (batches o) all []]
                (if-let [b (first bs)]
                  (let [[st v] (step/answered st (:tag b))
                        reps (mapv report (sort-by str (get-in b [:req :names])))
                        [st o2] (prove/batch-ended st (parse-long (second v)) {:pass (count (filter #(= :pass (:type %)) reps)) :fail (count (remove #(= :pass (:type %)) reps)) :error 0 :reports reps})]
                    (recur st (into (vec (rest bs)) (batches o2)) (into all o2)))
                  [st all])))
        [st all] (run st o)
        r (get (answers all) 1)]
    (is (false? (:green r)) (pr-str r))
    (is (= '[app.p-test/one app.p-test/three] (:red r)) "both red proofs named")
    (is (every? (set (keys (:results r))) '[app.p-test/one app.p-test/two app.p-test/three]) "every proof has its verdict")
    (is (= {} (:items st)) "nothing left queued")))

;; docs/merging.md#the-test-service: a tree's run (:judge, a session's or a landing's) is a verdict of the whole, so past a red it
;; still runs every item and names every red; an agent's turn with no proofs named is answered at its first red, as before
(deftest ^{:seon.test/estimate {:ticks 500000}} a-whole-runs-asker-hears-every-red-and-a-turns-its-first
  (let [one 'a.x-test/one two 'a.x-test/two
        red (fn [ts] {:pass 0 :fail (count ts) :error 0 :reports (mapv #(hash-map :type :fail :var (str "#'" %) :message "no") ts)})
        run (fn [mode]
              (let [[st0 o0] (feed (assoc (proc0) :fixed-budget 1) [[:req {:rid 1 :branch :b :t 5 :digest "d5" :agent :a1 :mode mode}]])]
                (loop [st st0 all o0 seen 0]
                  (if-let [m (first (filter :req (drop seen all)))]
                    (let [u (unwrap m)
                          r (if (get-in u [:req :plan?]) {:items {one "d1" two "d2"} :selected [one two]}
                              (red (sort-by str (or (seq (get-in u [:req :names])) (keys (get-in u [:req :items]))))))
                          [st o] (feed st [(answer-of m r)])]
                      (recur st (into all o) (inc (.indexOf all m))))
                    (:r (first (filter #(= :prove-answer (:op %)) all)))))))
        whole (run :judge) turn (run :turn)]
    (is (= [one two] (:red whole)) (pr-str (select-keys whole [:red :ran])))
    (is (= 2 (:ran whole)) "both items ran though the first went red")
    (is (= 1 (:ran turn)) "a turn that named no proofs stops at its first red")))

;; an agent's turn waits to act: its items go ahead of a batch's, however the batch's are valued
(defn- prio-of
  "The :prio each test batch in outs was asked of the :process owner at, by its names."
  [outs] (into {} (for [m outs :when (= :test (get-in m [:req :req :kind]))] [(get-in m [:req :req :seon.fx/ask :names]) (get-in m [:req :prio])])))

(deftest ^{:seon.test/estimate {:ticks 200000}} a-turns-item-goes-ahead-of-a-batchs
  ;; both asked of the owner in one grant, whose queue takes the higher :prio first: the turn's, however the batch's is valued
  (let [[st o1] (prove/ask-items (items-st 1) 1 :batch {['app.cheap-test/t "dc"] {:agent :a1 :ms 1}})
        [_ o2] (prove/ask-items st 2 :turn {['app.slow-test/u "ds"] {:agent :a2 :ms 100000}})
        p (prio-of (into o1 o2))]
    (is (> (p '#{app.slow-test/u}) (p '#{app.cheap-test/t})) (str "the turn's item is asked at the higher priority: " p))))

;; every wait ends: a batch whose run errored (a test child that died) answers each of its askers with that error, never silence
;; docs/merging.md#the-test-service: a share of the queue over the owner's budget: two items over a budget of two are two batches
(deftest ^{:seon.test/estimate {:ticks 200000}} a-worker-pulls-one-item-at-a-time
  (let [[_ outs] (prove/ask-items (items-st 2) 1 :batch {['app.x-test/a "da"] {:agent :a1 :ms 5} ['app.x-test/b "db"] {:agent :a1 :ms 5}})]
    (is (= [1 1] (mapv #(count (get-in % [:req :names])) (batches outs))) "a budget of two, one item each, though both are app.x-test's")))

;; docs/merging.md#the-test-service: of the askers waiting, the one nearest done is served first (shortest remaining work first), so
;; ten askers finish one by one rather than all late together; an older asker with more left waits, though it came first
(deftest ^{:seon.test/estimate {:ticks 200000}} the-asker-nearest-done-is-served-first
  (let [it {:agent :a1 :ms 10}
        [st _] (prove/ask-items (items-st 0) 1 :batch {['app.b-test/x "dx"] it ['app.b-test/y "dy"] it ['app.b-test/z "dz"] it})
        [_ o] (prove/ask-items st 2 :batch {['app.a-test/t "dt"] (assoc it :agent :a2)})
        p (prio-of o)]
    (is (every? #(> (p '#{app.a-test/t}) (val %)) (dissoc p '#{app.a-test/t})) (str "the one-item asker first, though asked second: " p))))

;; docs/merging.md#the-test-service: an asker's new request for the same slot at another digest supersedes its run in flight: its
;; waiter is answered :seon.prove/superseded, what only it queued leaves the queue, and the new digest is planned
(deftest ^{:seon.test/estimate {:ticks 200000}} a-newer-ask-of-a-slot-supersedes-the-run-in-flight
  (let [[st o] (feed (items-st 0) [[:req {:rid 1 :branch :b :digest "d1" :agent :a1}]])
        [st _] (feed st [(answer-of (first (asks o :tests)) {:items {'app.x-test/t "dt"} :selected ['app.x-test/t]})])
        _ (is (seq (:items st)) "the first run's item is queued")
        [st o2] (feed st [[:req {:rid 2 :branch :b :digest "d2" :agent :a1}]])
        r1 (:r (first (filter #(and (= :prove-answer (:op %)) (= 1 (:rid %))) o2)))]
    (is (= :seon.prove/superseded (:seon.error/type r1)) (pr-str r1))
    (is (every? (comp empty? :waiters) (vals (:items st))) "its item, already asked of the owner, has no one waiting on it")
    (is (= 1 (count (filter #(get-in % [:req :plan?]) (asks o2 :tests)))) "and the new digest is planned")
    (is (empty? (filter #(= :problem (:op %)) o2)) "a superseded run is no platform problem")))

(deftest ^{:seon.test/estimate {:ticks 200000}} a-batch-that-died-answers-its-askers-with-the-error
  (let [[st o1] (prove/ask-items (items-st 1) 7 :batch {['app.x-test/t "d1"] {:agent :a1 :ms 5}})
        b (first (batches o1))
        [st v] (step/answered st (:tag b))
        died {:seon.error/type :seon.test/fork-refused :seon.error/message "the test child could not be forked"}
        [st o2] (prove/batch-ended st (parse-long (second v)) died)
        r (get (answers o2) 7)]
    (is (false? (:green r)))
    (is (seon.contract/error? (get-in r [:results 'app.x-test/t])) "the asker holds the typed error for the item")
    (is (= {} (:items st)))))

;; docs/principles.md#6-nothing-changed-nothing-runs-a-change-costs-what-it-touched: a wall-time end read the machine, so the service
;; answers it as no verdict and a :platform problem (the core's own, loud), never a red its cache keeps (root.log 2026-11-14: 21 such)
(deftest ^{:seon.test/estimate {:ticks 200000}} a-wall-time-end-is-a-platform-problem-never-a-red
  (let [[st o1] (prove/ask-items (items-st 1) 7 :batch {['app.x-test/t "d1"] {:agent :a1 :ms 5}})
        b (first (batches o1))
        [st v] (step/answered st (:tag b))
        ended {:pass 0 :fail 0 :error 1
               :reports [{:type :error :var "#'app.x-test/t" :seon.error/type :seon.test/wall-spent :message "Uncaught exception, not in assertion."
                          :actual "the test ran past its wall bound of 36700 ms"}]}
        [st o2] (prove/batch-ended st (parse-long (second v)) ended)
        r (get-in (answers o2) [7 :results 'app.x-test/t])]
    (is (= :seon.prove/no-verdict (:seon.error/type r)) (str "no verdict, never a red: " (pr-str r)))
    (is (some #(and (= :problem (:op %)) (= :platform (:class %))) o2) "and a :platform problem, loud")))

;; docs/merging.md#the-test-service: one test with no verdict voids only its own. A run's answer was the first item error
;; (seon.prove/settle): one test ending at its wall bound (seon.procs-test/the-render-proc-asks-for-root-s-view-once-per-round-trip)
;; made the first backlog task's whole run an error, the agent held it as :seon.agent/test-error, and every proof read unknown, so
;; the task closed (context, 2026-11-14). Estimate: one run of two items through the proc ~0.3M.
(deftest ^{:seon.test/estimate {:ticks 500000}} a-test-with-no-verdict-voids-only-its-own
  (let [answer {:pass 1 :fail 0 :error 1 :ms 5 :digests {'a.b-test/t "d1" 'a.b-test/u "d2"}
                :reports [{:type :pass :var "#'a.b-test/t"}
                          {:type :error :var "#'a.b-test/u" :seon.error/type :seon.test/wall-spent :message "Uncaught exception, not in assertion."
                           :actual "the test ran past its wall bound of 36700 ms"}]}
        [st outs] (feed (proc0) [(req 1 :main 5)])
        [_ all] (run-through st outs answer)
        r (:r (first (filter #(= :prove-answer (:op %)) all)))]
    (is (not (seon.contract/error? r)) (str "the run answers its verdicts, not the one test's error: " (pr-str r)))
    (is (= 1 (:pass r)) "the test that passed counts")
    (is (= [:seon.prove/no-verdict] (keep #(when (= "#'a.b-test/u" (:var %)) (:seon.error/type %)) (:reports r)))
        "and the one ended at its wall bound is its own no-verdict")
    (is (not-any? #{'a.b-test/u} (:red r)) (str "a no-verdict is no red: " (pr-str (:red r))))
    (is (= ['a.b-test/u] (keys (:no-verdict r))) "it is named as no verdict, with why")))

;; docs/merging.md#the-test-service: a batch's own error (its child could not be forked, it died before a test began) is the platform
;; failing: each of its items has no verdict, and the error holds, typed, for its asker; never a red per item. Red before: run-answer
;; named every errored item in :red, and the gate (seon.fx/gate-answer) read each red as :fail: data's pilot, 2026-11-15 23:0x, met
;; the merge gate's answer of 280 :fail from one batch's error (root). Estimate: one run-answer and one gate-answer of 2 items, ~50k.
(deftest ^{:seon.test/estimate {:ticks 100000}} a-batchs-error-is-no-verdict-and-never-a-red
  (let [died {:seon.error/type :seon.test/fork-refused :seon.error/message "the test child could not be forked"}
        r (#'prove/run-answer {:selected ['a.b-test/t 'a.b-test/u] :digests {'a.b-test/t "d1" 'a.b-test/u "d2"}}
                              {'a.b-test/t died 'a.b-test/u died})
        g (fx/gate-answer r ['a.b-test/t 'a.b-test/u])]
    (is (= [] (:red r)) (str "no item ran, so none is red: " (pr-str (:red r))))
    (is (= #{'a.b-test/t 'a.b-test/u} (set (keys (:no-verdict r)))) "each is no verdict")
    (is (= :seon.test/fork-refused (get-in r [:no-verdict 'a.b-test/t :seon.error/type])) "naming the batch's own error")
    (is (= {} (:results g)) (str "the gate reads no :fail from it, so its branch keeps them deferred: " (pr-str g)))
    (is (= :seon.test/fork-refused (get-in g [:no-verdict 'a.b-test/u :seon.error/type])) "and the gate's answer carries why")
    ;; every reader of a run's tests reads them as no verdict (root's review of f4ca2f3a4c): the agent's record (seon.proofs/results) and
    ;; the done rule's ask of main (seon.reconcile/main-answer), where a red would make the change's red main's
    (is (= {} (proofs/results r)) "no result for a test that never ran")
    (is (= {:red #{} :unknown #{'a.b-test/u}} (select-keys (#'seon.reconcile/main-answer #{'a.b-test/u} r) [:red :unknown])) "main answered nothing of it: unknown, never main's red")))

;; docs/merging.md#the-test-service: each asker is told an item's verdict as it lands, before it is answered, so a session's
;; bin/prove prints each test as it ends; an item with no verdict is told as :no-verdict, never left out
(deftest ^{:seon.test/estimate {:ticks 200000}} each-asker-is-told-a-verdict-as-it-lands
  (let [it {:agent :a1 :ms 5}
        [st o1] (prove/ask-items (items-st 2) "k1" :batch {['app.x-test/t "d1"] it ['app.x-test/u "d2"] it})
        [st _] (prove/ask-items st "k2" :batch {['app.x-test/t "d1"] it})
        [bt bu] (sort-by #(str (get-in % [:req :names])) (batches o1))
        [st vt] (step/answered st (:tag bt))
        [st o2] (prove/batch-ended st (parse-long (second vt)) (green-of 'app.x-test/t))
        [st vu] (step/answered st (:tag bu))
        died {:seon.error/type :seon.test/fork-refused :seon.error/message "the test child could not be forked"}
        [_ o3] (prove/batch-ended st (parse-long (second vu)) died)
        told (fn [outs] (mapv #(select-keys % [:key :test :digest :status]) (filter #(= :prove-verdict (:op %)) outs)))]
    (is (= [{:key "k1" :test 'app.x-test/t :digest "d1" :status :pass} {:key "k2" :test 'app.x-test/t :digest "d1" :status :pass}] (told o2))
        "both askers of the one item are told its verdict")
    (is (= ["k2"] (keys (answers o2))) "k2, whose only item it was, is answered; k1 still waits on u")
    (is (< (.indexOf ^java.util.List (mapv :op o2) :prove-verdict) (.indexOf ^java.util.List (mapv :op o2) :asker-answered)) "told before it is answered")
    ;; the answer carries every verdict its asker was told: a streaming client writes from it what the events had not
    ;; brought before the answer (seon.server's prove-streamed), so none is owed by a race
    (is (= [{:test 'app.x-test/t :digest "d1" :status :pass}] (mapv #(select-keys % [:test :digest :status]) (:verdicts (get (answers o2) "k2"))))
        "k2's answer names the verdict it was told")
    (is (= [{:key "k1" :test 'app.x-test/u :digest "d2" :status :no-verdict}] (told o3)) "an item with no verdict is told so")))

;; docs/merging.md#the-test-service: a batch answer that names no verdict for an item it was granted is the core failing, so
;; it is a :platform problem with its evidence (the owner, 2026-11-13: "fail loud"): the test, its digest and the names the answer
;; did report. Red before: the item was told :no-verdict and nothing was filed. Estimate: two asks and one batch end ~0.2M.
(deftest ^{:seon.test/estimate {:ticks 300000}} a-batch-answer-missing-an-item-files-a-problem-with-its-evidence
  (let [it {:agent :a1 :ms 5}
        [st o1] (prove/ask-items (items-st 1) "k1" :batch {['app.x-test/t "d1"] it ['app.x-test/u "d2"] it})
        b (first (batches o1))
        [st v] (step/answered st (:tag b))
        [_ o2] (prove/batch-ended st (parse-long (second v)) (green-of 'app.x-test/t))
        problems (filterv #(= :problem (:op %)) o2)]
    (is (= #{'app.x-test/t 'app.x-test/u} (set (get-in b [:req :names]))) "one batch of both")
    (is (= [[:platform :seon.prove/no-verdict 'app.x-test/u "d2" ['app.x-test/t]]]
           (mapv (juxt :class (comp :seon.error/type :error) (comp :test :error) (comp :digest :error) (comp :reported :error)) problems))
        "the missing item is a problem naming it, its digest and what the answer did name")))

;; docs/merging.md#verdicts-are-a-cache: a green and a red at one digest read something its digest misses (load, a cold fixture, a
;; shared file: the owner, 2026-11-14), so it is no verdict and a :platform problem with its evidence, filed by the run whose plan
;; reads it, once a digest; ticks over ten times apart say its runs did different work. Red before: the flaky verdict judged red,
;; held every landing that reached it, and only a ten-times gap was filed. Estimate: three short feeds ~0.3M.
(deftest ^{:seon.test/estimate {:ticks 300000}} a-green-and-a-red-at-one-digest-is-a-platform-problem-never-a-red
  (let [flaky (fn [t g r] {:type :pass :flaky true :var (str "#'" t) :reused true :seon.proofs/red-failures [{:type :fail :message "1 is not 2"}]
                           :seon.proofs/ticks {:green g :red r}})
        plan {:items {} :selected ['app.x-test/t 'app.x-test/u 'app.x-test/v]
              :digests {'app.x-test/t "d1" 'app.x-test/u "d2" 'app.x-test/v "d3"}
              :reused-reports [(flaky 'app.x-test/t 1000000 30000000) (flaky 'app.x-test/u 1000000 9000000)
                               {:type :fail :var "#'app.x-test/v" :reused true :message "1 is not 2"}]}
        problems (fn [outs] (mapv (juxt :class (comp :seon.error/type :error) (comp :test :error) (comp :digest :error) (comp :ticks :error))
                                  (filter #(= :problem (:op %)) outs)))
        plan-of (fn [st rid t] (let [[st o] (feed st [(req rid :b t :a1)])
                                     m (first (filter #(get-in % [:req :plan?]) o))]
                                 (feed st [(answer-of m plan)])))
        [st o1] (plan-of (proc0) 1 5)
        [_ o2] (plan-of st 2 6)]
    (is (= [[:platform :seon.proofs/flaky 'app.x-test/t "d1" {:green 1000000 :red 30000000}]
            [:platform :seon.proofs/flaky 'app.x-test/u "d2" {:green 1000000 :red 9000000}]] (problems o1))
        "every flaky digest is a problem naming the test, its digest and both ticks; a red alone is a red, not a problem")
    (is (clojure.string/includes? (-> (filter #(= :problem (:op %)) o1) first :error :seon.error/message) "over ten times apart") "30x apart says so")
    (is (= 'app.x (-> (filter #(= :problem (:op %)) o1) first :error :owner)) "named for the namespace whose task file holds its work")
    (is (= [] (problems o2)) "the same digest read by the next run is not filed again")))

;; docs/flow.md, request and answer (flow's review, task seon.prove/the-services-asks-are-capped-by-its-answers-port): the service's
;; asks are answered on its :answers port, which holds :seon.config.root/reconciler-buf; past that many open, the api would park putting
;; an answer while the service parks putting an ask. So at the cap the service reads only :answers, as the fx owner leaves :in
;; unread at its bound. Red before: no input filter, the asks uncapped. Estimate: a few steps, ~0.1M ticks.
(deftest ^{:seon.test/estimate {:ticks 500000}} the-service-never-asks-more-than-its-answers-port-holds
  (with-redefs [seon.config/value (let [v seon.config/value] (fn [k & more] (if (= k :seon.config.root/reconciler-buf) 4 (apply v k more))))]
    (let [full (assoc (proc0) :pending (into {} (for [i (range 4)] [i {:k i}])))
          ;; any input steps the service; an answer to no ask it holds is counted stale and changes nothing else
          [st _] (prove/prove-proc full :answers {:tag :none :r nil})
          f (:clojure.core.async.flow/input-filter st)]
      (is (some? f) "an input filter is set")
      (is (and f (not (f :req)) (f :answers)) "at the cap it reads only its answers")
      (let [[st2 _] (prove/prove-proc (assoc st :pending {}) :answers {:tag :none :r nil})
            f2 (:clojure.core.async.flow/input-filter st2)]
        (is (or (nil? f2) (f2 :req)) "below it, requests are read again")))
    (testing "the cap holds at the ask site: a grant at one below it deals one batch, never a budget's worth (flow's review)"
      (let [items (into {} (for [i (range 8)] [[(symbol "app.c-test" (str "t" i)) (str "d" i)] {:agent (keyword (str "a" i)) :ms 5}]))
            [st _] (prove/ask-items (assoc (items-st 8) :pending {0 {:k 0} 1 {:k 1} 2 {:k 2}}) 1 :batch items)]
        (is (<= (count (:pending st)) 4) (str "open asks within the answers port: " (count (:pending st))))))))

(defn queue-of
  "Grant's argument at n: the service's state with n items queued for one batch asker and a budget of one. See
  docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map]]}
  [n]
  [(-> (items-st 1)
       (assoc :items (into {} (for [i (range n)] [[(symbol "app.q-test" (str "t" i)) (str "d" i)] {:agent :a1 :ms (inc (mod i 97)) :status :queued :waiters #{1} :at 0}]))
              :askers {1 {:prio :batch :left #{} :results {}}}))])

;; docs/merging.md#the-test-service: a grant asks every grantable item of the :process owner; what it costs per item queued
;; est: proof at 100/300/1000 items: fixed 2k + 500 an item, up to 3 measurements, plus baseline
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-grant-costs-what-is-queued
  (seon.cost/proof
    {:of 'seon.prove/grant :input 'seon.prove-test/queue-of
     :n "items queued, a budget of one"
     :work "one pass over the queued items for the most valuable (item-value, its age read from the grant it was queued at, and its waiters read only when an asker is a turn's), and its batch (batch-of): a budget of one, its share is every queued item, so two passes more (count, take) and each item's status, its place in the batch and in the request written; one pass more over the batch for its askers (a sorted set of each item's waiters, which the owner tells when the load holds it: an insert an item, ~130 ticks and 1 KB, the work the estimate before it missed); with two askers or more, one pass summing each asker's work left (an update a waiter) (linear in reads and writes)"
     :growth :linear
     ;; measured 2026-11-11 at 100 queued: 6,261 ticks and 19.0 KB a grant of one item, where aging every item it passed over, by
     ;; a lazy filter and apply max-key, took 43.2k ticks and 355.9 KB. A grant of a batch (2026-11-12) writes each item it takes
     ;; (its status, its place in the batch and the request: ~1.5 KB) and walks the queue twice more (a filter's seq, then a
     ;; destructured seq of entries: ~1.5 KB), and the askers' work left (one map update a waiter, ~1 KB); measured 588 ticks and
     ;; 4.2 KB an item at 100
     :per-n {:ticks 650 :bytes 5200}
     :fixed {:ticks 2000 :bytes 2000}
     :sizes [100 300 1000]}))

;; ---- a checkout's process tests, check steps and build as items (notes test-system-as-a-flow-2026-11-10.md, step 4) ----

(defn- tree-run
  "A session's run of checkout /ck at tree T (agent a1 holds its branch), with a budget of slots, its plan answered with plan:
  [st outputs of the plan's answer, the plan ask]."
  [slots plan]
  (let [[st o] (feed (items-st slots) [[:req {:rid 1 :branch :tree-T :digest "dT" :agent :a1 :checkout "/ck" :tree "T"}]])
        ask (first (asks o :tests))
        [st o2] (feed st [(answer-of ask plan)])]
    [st o2 ask]))
(defn- fx-asks [outs] (asks outs :fx))
(defn- seeded-run
  "tree-run, its tree's seed answered green: every step and process item needs the seed (it brings the checkout's program
  store to the tree), so what a test of the steps reads is granted after it. [st outputs past the seed, the plan ask]."
  [slots plan]
  (let [[st o ask] (tree-run slots plan)
        sd (first (filter #(= :seed (get-in % [:req :req :kind])) (fx-asks o)))]
    (if sd
      (let [[st o2] (feed st [(answer-of sd {:value {:pass 1 :fail 0 :error 0 :ms 1}})])] [st (into (vec (remove #{sd} o)) o2) ask])
      [st o ask])))
(defn- run-answers [outs] (mapv :r (filter #(= :prove-answer (:op %)) outs)))

;; docs/merging.md#the-test-service: a process test runs on its checkout's binary, so it waits for its tree's build
(deftest ^{:seon.test/estimate {:ticks 500000}} a-process-item-waits-for-its-trees-build-and-seed
  (let [[st o ask] (tree-run 4 {:items {} :process {'a.p-test/t "dp"} :build {:tree "T" :needed? true}})
        [b] (fx-asks o)]
    (is (true? (get-in ask [:req :process?])) "a checkout's run asks its agent for its process items")
    (is (= [{:kind :build :tree "T" :checkout "/ck" :name :build :digest "T" :binary? false :bound nil}] (mapv #(dissoc (get-in % [:req :req]) :seon.prove/batch) (fx-asks o)))
        "the build alone is granted, though three workers are free")
    (let [[st o1] (feed st [(answer-of b {:value {:pass 1 :fail 0 :error 0 :ms 900}})])
          [sd] (fx-asks o1)
          _ (is (= :seed (get-in sd [:req :req :kind])) "the build green: the seed next, which makes the process tests' store")
          [st o2] (feed st [(answer-of sd {:value {:pass 1 :fail 0 :error 0 :ms 2000}})])
          [p] (asks o2 :tests)]
      (is (empty? (fx-asks o2)) "no cold process of its own: nothing goes to root's effect owner")
      (is (= {:id :a1 :items {'a.p-test/t "dp"} :checkout "/ck"} (select-keys (:req p) [:id :items :checkout]))
          "the seed green: its process test is granted to the agent holding the branch, run in its fork on the checkout")
      (let [[_ o3] (feed st [(answer-of p {:pass 1 :fail 0 :error 0 :reports [{:type :pass :var "#'a.p-test/t"}] :ms 40})])
            [r] (run-answers o3)]
        (is (= [1 0 0 []] ((juxt :pass :fail :error :deferred) r)) "counted as a test, nothing deferred")
        (is (= [:test] (mapv :kind (filter #(= 'a.p-test/t (:test %)) (filter #(= :prove-verdict (:op %)) o3)))) "its verdict streams as a test's")))))

(deftest ^{:seon.test/estimate {:ticks 500000}} a-trees-green-build-and-seed-are-made-once-for-its-checkout
  ;; Web's read of step 4: a second run of one tree (another key: names or mode) queued the seed again, which removes
  ;; and remakes the store the first run's steps were cloning; the build alike relinks the binary its items run
  (let [[st o] (tree-run 4 {:items {} :process {'a.p-test/t "dp"} :build {:tree "T" :needed? true}})
        [st o1] (feed st [(answer-of (first (fx-asks o)) {:value {:pass 1 :fail 0 :error 0 :ms 900}})])
        [st o2] (feed st [(answer-of (first (fx-asks o1)) {:value {:pass 1 :fail 0 :error 0 :ms 2000}})])
        [st _] (feed st [(answer-of (first (asks o2 :tests)) {:pass 1 :fail 0 :error 0 :reports [{:type :pass :var "#'a.p-test/t"}] :ms 40})])
        [st o3] (feed st [[:req {:rid 2 :branch :tree-T :digest "dT" :agent :a1 :checkout "/ck" :tree "T" :names '#{a.p-test}}]])
        [_ o4] (feed st [(answer-of (first (asks o3 :tests)) {:items {} :process {'a.p-test/u "du"} :build {:tree "T" :needed? true}})])]
    (is (empty? (fx-asks o4)) "no build, no seed again")
    (is (= [{'a.p-test/u "du"}] (mapv #(get-in % [:req :items]) (remove #(get-in % [:req :plan?]) (asks o4 :tests))))
        "the second run's process test is granted at once")))

(deftest ^{:seon.test/estimate {:ticks 500000}} a-seed-that-kept-the-tree-only-is-not-a-seed-of-the-stores
  ;; a run of a laws step seeds the tree only (no check store, no process tests' store); a later run of that tree with a
  ;; process test needs both stores, so the checkout's green seed of the first kind is no seed of the second (:made)
  (let [[st o] (seeded-run 4 {:items {} :steps {"laws" {:digest "dl" :binary? false}} :build {:tree "T" :needed? false}})
        [st _] (feed st [(answer-of (first (fx-asks o)) {:value {:pass 1 :fail 0 :error 0 :ms 1}})])
        [st o2] (feed st [[:req {:rid 2 :branch :tree-T :digest "dT" :agent :a1 :checkout "/ck" :tree "T" :names '#{a.p-test}}]])
        [_ o3] (feed st [(answer-of (first (asks o2 :tests)) {:items {} :process {'a.p-test/t "dp"} :build {:tree "T" :needed? false}})])]
    (is (= [[:seed true]] (mapv #(vector (get-in % [:req :req :kind]) (get-in % [:req :req :binary?])) (fx-asks o3)))
        "the process test's run seeds the stores first")))

;; every wait ends: a red build is every item's that needs it, and none of them runs on a binary that was never built
(deftest ^{:seon.test/estimate {:ticks 500000}} a-red-build-answers-what-needs-it-with-its-error
  (let [[st o] (tree-run 4 {:items {} :process {'a.p-test/t "dp"} :steps {"smoke" {:digest "ds" :binary? true :bound 5000}} :build {:tree "T" :needed? true}})
        [b] (fx-asks o)
        [_ o2] (feed st [(answer-of b {:value {:pass 0 :fail 1 :error 0 :failures [{:type :fail :message "bin/build exited 1"}]}})])
        [r] (run-answers o2)]
    (is (= 1 (count (fx-asks o))) "only the build was granted")
    (is (= [] (fx-asks o2)) "and after it, nothing")
    (is (= :seon.prove/build-red (:seon.error/type r)) (pr-str r))))

;; docs/merging.md#the-test-service: one owner admits every kind: a build and a test are both asked of the :process owner, which
;; bounds them together by the processes each holds (seon.fx/admits?)
(deftest ^{:seon.test/estimate {:ticks 500000}} one-bound-covers-builds-and-tests
  (let [[_ o] (tree-run 1 {:items {'a.b-test/t "dt"} :process {'a.p-test/t "dp"} :build {:tree "T" :needed? true}})
        sent (filterv #(= :fx (get-in % [:req :op])) o)]
    (is (= 2 (count sent)) "the build and the plain test, both asked at once (the process test waits for its build)")
    (is (= #{:process} (set (map #(get-in % [:req :kind]) sent))) "of the one owner")))

;; docs/merging.md#the-test-service: a fork's start and what its first test makes (a process test's root) are paid once a
;; batch: the queue goes to the free workers in shares, never one test a fork (measured 2026-11-12: one system test 20 s in a
;; fresh fork, each after it 0.5 s)
(deftest ^{:seon.test/estimate {:ticks 500000}} a-grant-is-a-share-of-the-queue-for-one-fork
  (let [items (into {} (for [i (range 8)] [(symbol "a.b-test" (str "t" i)) (str "d" i)]))
        [_ o] (tree-run 4 {:items items :build {:tree "T" :needed? false}})
        grants (mapv #(get-in % [:req :items]) (remove #(get-in % [:req :plan?]) (asks o :tests)))]
    (is (= [2 2 2 2] (mapv count grants)) (str "8 tests over a budget of 4: 4 forks of 2: " (pr-str grants)))
    (is (= items (apply merge grants)) "every test granted once")))

;; docs/merging.md#the-test-service: a step on the binary needs its tree's seeded store, made once a tree after the build; a red seed is its steps'
(deftest ^{:seon.test/estimate {:ticks 500000}} a-binary-step-waits-for-the-seed-and-a-red-seed-is-its-error
  (let [[st o] (tree-run 4 {:items {} :process {'a.p-test/t "dp"} :steps {"smoke" {:digest "ds" :binary? true :bound 5000}} :build {:tree "T" :needed? true}})
        [b] (fx-asks o)
        [st o2] (feed st [(answer-of b {:value {:pass 1 :fail 0 :error 0}})])
        kinds (fn [outs] (sort (map #(get-in % [:req :req :kind]) (fx-asks outs))))]
    (is (= [:build] (kinds o)))
    (is (= [:seed] (kinds o2)) "the build green: the seed alone; the step and the process test both need it (it makes the process tests' store)")
    (let [seed (first (filter #(= :seed (get-in % [:req :req :kind])) (fx-asks o2)))
          [_ o3] (feed st [(answer-of seed {:value {:pass 0 :fail 1 :error 0}})])
          [r] (run-answers o3)]
      (is (= {:kind :seed :name :seed :digest "T"} (select-keys (get-in seed [:req :req]) [:kind :name :digest])))
      (is (= [] (fx-asks o3)) "neither the step nor the process test is ever granted")
      (is (= [:seon.prove/build-red :seed] [(:seon.error/type r) (:kind r)]) (pr-str r)))))

;; docs/merging.md#the-test-service: a red step is the run's red by its name (a step's is no test var, so its failing report named
;; nothing), and a red run is not remembered: the same key asked again runs again. Red before: :red [] and the run remembered green
(deftest ^{:seon.test/estimate {:ticks 500000}} a-red-step-is-named-red-and-never-remembered
  (let [[st o] (seeded-run 4 {:items {} :steps {"laws" {:digest "dl" :binary? false :bound 100}} :build {:tree "T" :needed? false}})
        [s] (fx-asks o)
        [st o2] (feed st [(answer-of s {:value {:pass 0 :fail 1 :error 0 :failures [{:type :fail :message "measures FAILED"}]}})])
        [r] (run-answers o2)
        [_ o3] (feed st [[:req {:rid 2 :branch :tree-T :digest "dT" :agent :a1 :checkout "/ck" :tree "T"}]])]
    (is (= [["laws"] 0 1] ((juxt :red :pass :fail) r)) (pr-str r))
    (is (empty? (run-answers o3)) "asked again, it is not answered from a remembered green")
    (is (= 1 (count (filter #(get-in % [:req :plan?]) (asks o3 :tests)))) "it is planned again")))

;; ---- admission is the :process owner's (docs/config.md#the-test-services-three-limits) --------------------------------------

;; docs/merging.md#the-test-service: the service holds no admission: every grantable item is asked of root's :process effect owner
;; at once, with its class, so the owner admits it by the processes that class holds (seon.fx/admits?); a plain test's batch
;; declares its one child; a class that boots a root declares nothing, so it runs alone until its peak is read
(deftest ^{:seon.test/estimate {:ticks 500000}} every-grantable-item-is-asked-of-the-process-owner-with-its-class
  (let [plain (into {} (for [i (range 5)] [[(symbol "app.a-test" (str "t" i)) (str "d" i)] {:agent (keyword (str "a" i)) :ms 5}]))
        procs (into {} (for [i (range 3)] [[(symbol "app.r-test" (str "p" i)) (str "p" i)] {:agent (keyword (str "r" i)) :process? true :tree "t" :checkout "/c" :ms 5}]))
        [_ outs] (prove/ask-items (proc0) 1 :batch (merge plain procs))
        sent (filterv #(= :fx (get-in % [:req :op])) outs)]
    (is (= 8 (count sent)) "all eight asked at once, none held back by the service")
    (is (every? #(= :process (get-in % [:req :kind])) sent))
    (is (= {[:test false false] 5 [:test true false] 3} (frequencies (map #(get-in % [:req :seon.fx/class]) sent))))
    (is (= #{1} (set (keep #(get-in % [:req :seon.fx/processes]) sent))) "a plain batch declares its one child")
    (is (= 5 (count (keep #(get-in % [:req :seon.fx/processes]) sent))) "a root-booting class declares nothing: alone until read")))

;; docs/merging.md#the-test-service: the service deals an agent's queued tests over the owner's max-workers (seon.fx/limits of the
;; config in force), a share a fork
(deftest ^{:seon.test/estimate {:ticks 500000}} the-queue-is-dealt-over-the-owners-budget
  (let [budget (:max-workers (fx/config-limits))
        [_ outs] (prove/ask-items (proc0) 1 :batch (into {} (for [i (range 10)] [[(symbol "app.s-test" (str "t" i)) (str "d" i)] {:agent :a1 :ms 5}])))]
    (is (= (min 10 budget) (count (batches outs))) "ten items over the config's max-workers, read at the grant, a share each")
    (testing "a dial turned deals the next grant by it, no restart"
      (with-redefs [fx/config-limits (fn [] {:max-workers 2 :budget 20 :max-load nil})]
        (is (= 2 (count (batches (second (prove/ask-items (proc0) 1 :batch (into {} (for [i (range 10)] [[(symbol "app.s-test" (str "u" i)) (str "e" i)] {:agent :a1 :ms 5}]))))))))))
    (is (= 10 (count (mapcat #(get-in % [:req :names]) (batches outs)))) "every item asked")))

(deftest ^{:seon.test/estimate {:ticks 300000}} a-batchs-error-that-names-no-test-reaches-its-answer
  ;; docs/merging.md#the-test-service. Found 2026-11-12 (runtime's land): agent-checks' first prove answered {:error 1} with only
  ;; its two :pass reports: run-answer kept an item's reports by its test var, so a run's report naming no test was counted and dropped
  (let [held {:type :error :exit {:signal 9} :message "test process died: {:signal 9}"}
        answer (-> test-run-answer (assoc :error 1) (update :reports conj held))
        [st outs] (feed (proc0) [(req 1 :main 5)])
        [_ all] (run-through st outs answer)
        r (:r (first (filter #(= :prove-answer (:op %)) all)))]
    (is (= 1 (:error r)) "the error is counted")
    (is (= [held] (filterv :exit (:reports r))) (str "and its report is in the answer, once: " (pr-str (:reports r))))))

;; docs/merging.md#a-merge-runs-what-the-change-reaches: the judge's run through the test service answers, for each test it defers to
;; the merge check, that test's digest at the task's base, as the agent's plan computed it from the base's program. The merge check
;; recorded them on the task (:seon.task/deferred), and asks main of each red by name (seon.reconcile/ask-main). Red before: the service built its answer without it, so the first real task
;; on the store made at 06:46 carried a nil base digest for all 244 deferred tests, read as "new on the branch", and every red held it
(deftest ^{:seon.test/estimate {:ticks 200000}} a-runs-answer-carries-each-deferred-tests-base-digest
  (let [base {:deferred ['a.b-test/p 'a.b-test/q] :deferred-digests {'a.b-test/p "bp" 'a.b-test/q nil} :selected [] :at-judge 0}
        r (#'seon.prove/run-answer base {})]
    (is (= ['a.b-test/p 'a.b-test/q] (:deferred r)))
    (is (= {'a.b-test/p "bp" 'a.b-test/q nil} (:deferred-digests r)) "each deferred test's base digest, nil only for a test new on the branch")))

(deftest ^{:seon.test/estimate {:ticks 500000}} a-granted-items-pass-reaches-the-service
  ;; docs/merging.md#the-test-service. Found 2026-11-12 (data's task 1): an agent's run of a granted item kept, in its answer, a
  ;; pass report only for the task's proofs and the tests named (seon.proofs/answer), so an item that was neither and passed read
  ;; as "answered no verdict" (seon.prove/batch-ended), and the agent's task died before its first turn. What the agent's
  ;; answer keeps is what its asker reads: its proofs, the tests named, and the items granted (seon.agent/asked)
  (let [t 'a.b-test/t
        m {:op :tests :items {t "d1"}}
        run {:pass 1 :fail 0 :error 0 :reports [{:type :pass :var (str "#'" t)}] :digests {t "d1"}}
        ans (proofs/answer run (seon.agent/asked m #{'x.y-test/proof} [t]))
        ended (atom nil)]
    (with-redefs [prove/item-ended (fn [st _ r] (reset! ended r) [st []]) prove/grant (fn [st] [st []])]
      (#'prove/batch-ended {:batches {7 [[t "d1"]]} :items {[t "d1"] {:kind :test}}} 7 ans))
    (is (= 1 (:pass @ended)) (str "the item's pass is its verdict, never no-verdict: " (pr-str @ended)))))

(deftest ^{:seon.test/estimate {:ticks 100000}} a-grant-of-no-items-runs-nothing
  ;; docs/merging.md#the-test-service. Found 2026-11-12: an agent given a grant with empty :items ran every test it planned, 1,765
  ;; of them: empty read as not given. Given and empty selects nothing; not given (nil) plans
  (let [sel (fn [items] (#'seon.agent/grant-selection nil items (fn [] {:run [:everything]})))]
    (is (= [] (:run (sel {}))) "empty items: nothing runs")
    (is (= [:everything] (:run (sel nil))) "no items given: the run plans its own")))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-removed-test-that-no-longer-loads-fails-alone-and-only-when-it-runs
  ;; docs/merging.md#the-test-service. Found 2026-11-12 (tests-bootstrap): a test child defined every test the branch removed, from
  ;; main's source, before running anything; one naming a var the branch also removed threw, and every child of the branch ended
  ;; with one report naming no test: a process test granted there ran nothing. Only this run's removed tests load, each failure its own
  (let [removed {'rm.gone-test/a "(clojure.test/deftest a (clojure.test/is (#'clojure.string/no-such-fn)))"
                 'rm.gone-test/b "(clojure.test/deftest b (clojure.test/is true))"
                 'rm.gone-test/c "(clojure.test/deftest c (clojure.test/is (#'clojure.string/no-such-fn-either)))"}]
    (try
      (let [unloaded (#'seon.agent/load-removed! removed '[rm.gone-test/a rm.gone-test/b])]
        (is (= '#{rm.gone-test/a} (set (keys unloaded))) "the one that does not load is named; the other loads")
        (is (re-find #"does not load on this branch" (get unloaded 'rm.gone-test/a)))
        (is (some? (resolve 'rm.gone-test/b)) "a removed test this run runs is defined")
        (is (nil? (resolve 'rm.gone-test/c)) "one this run does not run is not loaded"))
      (finally (remove-ns 'rm.gone-test)))))

;; docs/merging.md#the-test-service: the ping says what a run is doing, so a slow run is never read as a lost one (context's seon_done
;; of 2026-11-16 read 48 :queued, every item counted, while 32 ran: result/b6fceafb). Its counts are state-view's, the one reading of
;; the queue. Red first: the ping counted every item as queued. Estimate: proc0 reads the binary program's digest once (~7.9M, docs/cost.md),
;; then a map of three items read twice (~20k): 10,000,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 10000000}} the-ping-tells-running-items-from-queued-ones
  (let [st (assoc (proc0) :items {['a.x-test/one "d1"] {:status :running :waiters #{1}}
                                  ['a.x-test/two "d2"] {:status :queued :waiters #{1}}
                                  ['a.x-test/three "d3"] {:status :queued :waiters #{1}}})
        ping ((:ping-map-fn (prove/prove-proc)) st)]
    (is (= [2 1] [(:queued ping) (:running ping)]) (pr-str (select-keys ping [:queued :running])))))

;; docs/merging.md#the-test-service: process tests share a child and the test root its first one boots (~20 s a root, ~0.5 s a test
;; after), so one agent's queued process items go out as one batch, never a share each: a share each booted a root a test, 15 at
;; once at ~450 MB, and a session's seon_done overran 300 s in the gate's first wave (web's end-to-end land, 2026-11-16). In-process
;; items keep their shares. Red first: process items were dealt like any (ten over the budget, a share each). Estimate: two grants
;; over ten items, the proc's start (~8M binary-program read once): 10,000,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 10000000}} process-items-of-one-agent-share-one-batch
  (let [[_ outs] (prove/ask-items (proc0) 1 :batch (into {} (for [i (range 10)] [[(symbol "app.p-test" (str "t" i)) (str "d" i)] {:agent :a1 :ms 5 :process? true}])))]
    (is (= 1 (count (batches outs))) (str "ten process items, one batch: " (count (batches outs))))
    (is (= 10 (count (mapcat #(get-in % [:req :names]) (batches outs)))) "every item asked")))
