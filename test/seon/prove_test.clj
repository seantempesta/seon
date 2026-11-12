(ns seon.prove-test
  "The test service (seon.prove): admission as a step-fn (one run per checkout, the latest request
  of a branch wins, a running key is joined, every waiter answered once); a run writes nothing to the
  store (its agent caches its verdicts by digest: seon.results-test).
  Run: bin/test seon.prove-test"
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d]
            [seon.test-store :as ts] [clojure.java.shell :as sh] [seon.agent-test :as at] [clojure.edn] [seon.prove :as prove] [seon.step :as step] [seon.agent] [seon.cost] [seon.proofs :as proofs] [seon.program] [seon.config]))

;; the service's state as a test starts it: the shipped worker count, and no load bound unless the test sets one, so no
;; verdict here reads the machine's load (docs/cost.md#what-is-measured: no assertion the load can turn red). prove-proc's own
;; init reads the config in force: in an agent of the standing system, the main tree's local.edn (max-load 18.0 turned five
;; of these red on a busy machine, 2026-11-11)
(defn- proc0 [] (dissoc (prove/prove-proc {:conn nil}) :max-load))

(defn- feed
  "State st after the messages ([in m] ...) in order, with every output it made (the :to-api messages)."
  [st msgs]
  (reduce (fn [[st outs] [in m]] (let [[st o] (prove/prove-proc st in m)] [st (into outs (:to-api o))])) [st []] msgs))

(defn- req
  "A request for branch at tx t, the digest of what it reads moving with t (a change at each tx)."
  [rid branch t & [agent]] [:req (cond-> {:rid rid :branch branch :t t :digest (str "d" t)} agent (assoc :agent agent))])
(defn- asks [outs op] (filterv #(= op (get-in % [:req :op])) outs))
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
        (let [op (get-in m [:req :op])
              ;; the service plans a run (its agent's :tests with :plan?) before it asks for each item by name
              plan (if (or (seon.contract/error? answer) (not (map? answer))) answer
                     {:items (:digests answer) :selected (vec (keys (:digests answer)))})
              r (case op :spawn {:id :p1} :tests (if (get-in m [:req :plan?]) plan answer) :write {:ok true :t 6} :kill {:killed :p1})
              [st o] (feed st [(answer-of m r)])]
          (recur st (into all o) (inc (.indexOf all m))))
        [st all]))))

(deftest a-running-key-is-joined-and-every-waiter-is-answered-once
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

(deftest a-commit-starts-no-run-of-main-unless-the-config-says-so
  ;; docs/merging.md#the-test-service: the writer's commit notice records main's t; with :seon.config.proofs/prove-main-on-commit
  ;; (on since 2026-11-12) a commit that moves the program starts one run of main, which every branch's plan then reuses, and a
  ;; data commit none. Off, neither runs anything, and a session's request for main runs as ever, at the t the notice recorded
  (let [db (ts/test-db)
        dg (seon.program/program-digest db)
        commits [[:main {:t 5 :ops [[:db/add 900 :task/name "x" 5 true]] :db db}]
                 [:main {:t 6 :ops [[:db/add 901 :form/src "(defn f [] 1)" 6 true]] :db db}]]
        value seon.config/value
        with-on (fn [on? f] (with-redefs [seon.config/value (fn [k] (if (= k :seon.config.proofs/prove-main-on-commit) on? (value k)))
                                          seon.db/at-commit (fn [_ _] db)]
                              (f)))]
    (with-on true #(let [[_ o] (feed (proc0) commits)]
                     (is (= 1 (count (asks o :spawn))) "on: the program commit starts one run of main, the data commit none")))
    (with-on false
      #(let [[st o] (feed (proc0) commits)]
         (is (empty? o) "off: no run from either commit")
         (is (= 6 (:main-t st)))
         (let [[st o2] (feed st [[:req {:rid 7 :branch :main :digest dg :mode :main}]])
               [_ all] (run-through st o2 test-run-answer)]
           (is (= 1 (count (asks o2 :spawn))) "a session's request runs main")
           (is (= [7] (waiters-answered all)) "and is answered once, at its end")
           (is (= 0 (count (asks all :write))) "nothing written to the store: its agent cached its verdicts"))))))

(deftest an-answer-whose-child-died-is-never-remembered
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

(deftest a-run-is-keyed-by-what-it-reads
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

(deftest done-holds-only-what-can-be-asked-again
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

(deftest a-session-and-an-agents-turn-for-one-digest-are-one-run
  ;; the session's seon_prove for agent a3's branch at tx 7, and a3's turn asking for the same
  (let [[st outs] (feed (proc0) [(req 10 :agent-3 7 :a3) (req 11 :agent-3 7 :a3)])]
    (is (= 1 (count (asks outs :tests))) "one plan request, to the agent that holds the branch")
    (is (= {:op :tests :id :a3 :mode :judge :plan? true} (:req (first (asks outs :tests)))))
    (is (empty? (asks outs :spawn)) "the agent runs its own branch: no prove agent")
    (let [[_ all] (run-through st outs test-run-answer)]
      (is (= [10 11] (waiters-answered all)))
      (is (= [true nil] (mapv #(get-in % [:req :plan?]) (asks all :tests))) "one plan and one run of its item, for both"))))

(deftest a-failed-run-answers-its-waiters-with-the-error
  (let [[st outs] (feed (proc0) [(req 1 :a 1 :aa) (req 2 :a 1 :aa)])
        err (seon.contract/error :seon.system/no-agent 'seon.system/run-tests! "no live agent" {})
        [st all] (run-through st outs err)]
    (is (= [1 2] (waiters-answered all)))
    (is (every? seon.contract/error? (map :r (filter #(= :prove-answer (:op %)) all))))))


;; docs/merging.md#verdicts-are-a-cache: the daily run is what catches what a digest can't see, so what it finds is told, not only cached
(deftest a-red-in-the-full-run-is-a-problem
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

(deftest the-full-run-is-a-run-of-the-tests-effect-recorded-on-main
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

(deftest an-agents-run-leaves-every-process-test-to-the-judge
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

(deftest an-error-is-answered-recorded-and-never-remembered
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

(deftest a-run-whose-agent-ended-is-answered-not-a-platform-problem
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

(deftest a-spawn-failure-and-a-prove-agent-that-dies-are-answered-and-recorded
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

(deftest a-stale-answer-is-counted
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
          (let [m (open (mod pick (count open))) op (get-in m [:req :op])
                r (if (zero? (mod (quot pick 5) 3))
                    (seon.contract/error :seon.system/ended #'seon.prove/prove-proc "the agent ended" {:agent :a1})
                    (case op :spawn {:id :p1} :tests test-run-answer :write {:ok true :t 6} :kill {:killed :p1}))
                [st o] (feed st [(answer-of m r)])]
            (recur (inc i) x st (into (vec (remove #(= m %) open)) (filter :req o)) asked (into answers (waiters-answered o)))))
        ;; drain: the api answers what is left with successes
        (loop [st st open open answers answers n 0]
          (if (and (seq open) (< n 200))
            (let [m (first open) op (get-in m [:req :op])
                  [st o] (feed st [(answer-of m (case op :spawn {:id :p1} :tests test-run-answer :write {:ok true :t 6} :kill {:killed :p1}))])]
              (recur st (into (subvec open 1) (filter :req o)) (into answers (waiters-answered o)) (inc n)))
            [st asked answers]))))))

(deftest every-request-is-answered-once-whatever-order-the-api-answers-in
  ;; a Claude session's or an agent's turn waits on the service for ever if one request it took is
  ;; never answered (three hangs: an agent parked in seon.agent/prove! for the answer to its :prove).
  ;; 300 interleavings of requests and answers, errors among them: each request answered exactly once
  ;; and the service idle at the end
  (doseq [seed (range 1 301)]
    (let [[st asked answers] (model-run seed 14)]
      (is (= (sort asked) (sort answers)) (str "seed " seed ": each request answered, once: asked " (vec asked) " answered " (vec (sort answers))))
      (is (and (nil? (:running st)) (empty? (:next st))) (str "seed " seed ": the service ends idle: " (pr-str (select-keys st [:running :next])))))))

(deftest the-service-reports-what-it-holds-when-that-changes
  ;; a hang in a run's chain is read from what the service holds (seon.system keeps it for a stall's
  ;; evidence): each run in flight with its stage and who waits, the queue, the free workers, and the api's asks it has open
  (let [state-of (fn [outs] (:r (last (filter #(= :prove-state (:op %)) outs))))
        [st outs] (feed (proc0) [(req 1 :main 5) (req 2 :main 5)])
        s1 (state-of outs)]
    (is (= {"main@d5" {:stage :spawn :waiting 2}} (:runs s1)))
    (is (= [[:spawn "main@d5"]] (:open s1)) "the ask it waits on")
    (let [spawn (first (asks outs :spawn))
          [st o] (feed st [(answer-of spawn {:id :p1})])
          s2 (state-of o)]
      (is (= [{:stage :plan :waiting 2} [[:plan "main@d5"]]] [(get-in s2 [:runs "main@d5"]) (:open s2)]) "the spawn answered: the plan ask is the one open")
      (testing "a message that changes nothing it holds reports nothing"
        (let [[_ o2] (feed st [(answer-of {:tag 9999} {:ok true})])]
          (is (empty? (filter #(= :prove-state (:op %)) o2))))))))

(deftest two-runs-on-one-checkout-each-end-with-their-own-result
  ;; two bin/prove runs at once on one checkout wrote the same target/prove (shard files, logs):
  ;; one run's start removed the other's shards and a plain run destroyed a --merge run's logs.
  ;; Each run writes under a directory of its own, named on its first line, ends with its own
  ;; complete result (its output holds its plan line and PROVE OK), and a green run's directory is
  ;; removed with the run: its logs are read by no one (a red or killed run's stay, the evidence)
  ;; run from the checkout this process runs from (seon.config/checkout), never the working directory: a test
  ;; child of main's boot run works in its own run directory, where bin/prove is not (the thread that
  ;; threw never delivered its promise, and the whole boot run waited on it for good: 2026-10-30). A
  ;; future's deref rethrows what its body threw, so a run that cannot start ends this test with that error
  (let [ck (seon.config/checkout)
        run (fn [] (clojure.java.shell/sh (str ck "/bin/prove") "--tests" "seon.step-test" :dir ck))
        fa (future (run)) fb (future (run))
        rs [@fa @fb]
        dir (fn [r] (second (re-find #"prove: run \S+, logs in (\S+)" (:out r))))]
    (doseq [r rs]
      (is (zero? (:exit r)) (str (:out r) (:err r)))
      (is (some? (dir r)) "a run says where its logs are")
      (is (clojure.string/includes? (:out r) "PROVE OK"))
      (is (re-find #"prove: \d+ keys" (:out r)) "and holds its own plan line"))
    (is (not= (dir (first rs)) (dir (second rs))) "each run has a directory of its own")
    (doseq [r rs]
      (is (not (.exists (java.io.File. ^String (dir r)))) "and a green run's directory is gone with it"))))

;; ---- items: the service's unit of work (notes test-system-as-a-flow-2026-11-10.md, step 2) ----------------------------

(defn- items-st [workers] (assoc (proc0) :workers workers))
(defn- free [st] (:slots (#'prove/state-view st)))
(defn- batches [outs] (filterv #(= :tests (get-in % [:req :op])) outs))
(defn- answers [outs] (into {} (for [o outs :when (= :asker-answered (:op o))] [(:asker o) (:r o)])))
(def ^:private green-of (fn [& ts] {:pass (count ts) :fail 0 :error 0 :reports (mapv #(hash-map :type :pass :var (str "#'" %)) ts)}))

;; docs/merging.md#the-test-service: ten askers of one item run it once, and each is answered when its last item is green
(deftest an-item-many-ask-runs-once-and-answers-each
  (let [it {:agent :a1 :ms 50}
        [st o1] (prove/ask-items (items-st 4) 1 :batch {['app.x-test/t "d1"] it})
        [st o2] (prove/ask-items st 2 :batch {['app.x-test/t "d1"] it})
        _ (is (= 1 (count (batches (into o1 o2)))) "the second asker joins the item: one batch, not two")
        _ (is (= {} (answers (into o1 o2))) "nobody is answered before it ends")
        [st m] (step/answered st (:tag (first (batches o1))))
        [st o3] (prove/batch-ended st (parse-long (second m)) (green-of 'app.x-test/t))]
    (is (= {1 true 2 true} (update-vals (answers o3) :green)) "both answered green from the one run")
    (is (= {} (:items st)) "and the item has left the service: its verdict is the cache's")
    (is (= 4 (free st)) "its slot free again")))

;; an asker wants to know whether its tests pass: its first red answers it at once, and the work only it wanted is dropped
(deftest a-run-held-for-the-load-is-told-so-and-told-when-it-resumes
  ;; Point, 2026-11-11: a --root run held for the machine's load printed nothing, which reads as a hang. With max-load 0 any
  ;; load holds the second item while the first runs: the asker is told once, and told again when the next grant goes
  (let [held (fn [outs] (filterv #(= :prove-held (:op %)) outs))
        [st o1] (prove/ask-items (assoc (items-st 2) :max-load 0.0) 1 :batch {['app.a-test/t "da"] {:agent :a1 :ms 10} ['app.b-test/u "db"] {:agent :a1 :ms 10}})
        b (first (batches o1))
        [st v] (step/answered st (:tag b))
        [_ o2] (prove/batch-ended st (parse-long (second v)) (green-of (first (get-in b [:req :names]))))]
    (is (= 1 (count (batches o1))) "one granted, the other held")
    (is (= [[1 true 1]] (mapv (juxt :key :held :queued) (held o1))) "the asker told once that one item waits for the load")
    (is (= [[1 false]] (mapv (juxt :key :held) (held o2))) "and told it resumed when the held item is granted")
    (is (= 1 (count (batches o2))))))

(deftest a-red-answers-its-asker-at-once-and-drops-what-only-it-wanted
  (let [[st o1] (prove/ask-items (items-st 1) 1 :batch {['app.a-test/t "da"] {:agent :a1 :ms 10} ['app.b-test/u "db"] {:agent :a1 :ms 10}})
        b (first (batches o1))
        [st v] (step/answered st (:tag b))
        red {:pass 0 :fail 1 :error 0 :reports [{:type :fail :var (str "#'" (first (get-in b [:req :names]))) :message "no"}]}
        [st o2] (prove/batch-ended st (parse-long (second v)) red)]
    (is (= 1 (count (batches o1))) "one slot: one batch at a time")
    (is (false? (:green (get (answers o2) 1))) "the first red answers its asker, red")
    (is (= [] (batches o2)) "the other item, which only it wanted, is never run")
    (is (= {} (:items st)) "and has left the queue")))

(deftest every-proof-has-its-verdict-though-one-goes-red
  ;; Sean, 2026-11-11 ("have them fix that"): an agent saw only its first red, a proof each turn. Past a red the asker keeps
  ;; waiting for its own proofs and is answered at the last, every red named; what is no proof of its own it stops waiting on
  (let [p (fn [n] [(symbol "app.p-test" n) (str "d" n)])
        items (into {(p "x") {:agent :a1 :ms 10}} (for [n ["one" "two" "three"]] [(p n) {:agent :a1 :ms 10}]))
        [st o] (prove/ask-items (items-st 1) 1 :batch items '#{app.p-test/one app.p-test/two app.p-test/three})
        report (fn [t] (if (#{"two" "x"} (name t)) {:type :pass :var (str "#'" t)} {:type :fail :var (str "#'" t) :message "no"}))
        ;; end each granted batch in turn, a verdict for each of its tests: red for one and three, green for two and x
        run (fn [st o]
              (loop [st st o o all []]
                (if-let [b (first (batches o))]
                  (let [[st v] (step/answered st (:tag b))
                        reps (mapv report (sort-by str (get-in b [:req :names])))
                        [st o2] (prove/batch-ended st (parse-long (second v)) {:pass (count (filter #(= :pass (:type %)) reps)) :fail (count (remove #(= :pass (:type %)) reps)) :error 0 :reports reps})]
                    (recur st o2 (into all o2)))
                  [st all])))
        [st all] (run st o)
        r (get (answers all) 1)]
    (is (false? (:green r)) (pr-str r))
    (is (= '[app.p-test/one app.p-test/three] (:red r)) "both red proofs named")
    (is (every? (set (keys (:results r))) '[app.p-test/one app.p-test/two app.p-test/three]) "every proof has its verdict")
    (is (= {} (:items st)) "nothing left queued")))

;; docs/merging.md#the-test-service: a tree's run (:judge, a session's or a landing's) is a verdict of the whole, so past a red it
;; still runs every item and names every red; an agent's turn with no proofs named is answered at its first red, as before
(deftest a-whole-runs-asker-hears-every-red-and-a-turns-its-first
  (let [one 'a.x-test/one two 'a.x-test/two
        red (fn [ts] {:pass 0 :fail (count ts) :error 0 :reports (mapv #(hash-map :type :fail :var (str "#'" %) :message "no") ts)})
        run (fn [mode]
              (let [[st0 o0] (feed (assoc (proc0) :workers 1) [[:req {:rid 1 :branch :b :t 5 :digest "d5" :agent :a1 :mode mode}]])]
                (loop [st st0 all o0 seen 0]
                  (if-let [m (first (filter :req (drop seen all)))]
                    (let [r (if (get-in m [:req :plan?]) {:items {one "d1" two "d2"} :selected [one two]}
                              (red (sort-by str (or (seq (get-in m [:req :names])) (keys (get-in m [:req :items]))))))
                          [st o] (feed st [(answer-of m r)])]
                      (recur st (into all o) (inc (.indexOf all m))))
                    (:r (first (filter #(= :prove-answer (:op %)) all)))))))
        whole (run :judge) turn (run :turn)]
    (is (= [one two] (:red whole)) (pr-str (select-keys whole [:red :ran])))
    (is (= 2 (:ran whole)) "both items ran though the first went red")
    (is (= 1 (:ran turn)) "a turn that named no proofs stops at its first red")))

;; an agent's turn waits to act: its items go ahead of a batch's, however the batch's are valued
(deftest a-turns-item-goes-ahead-of-a-batchs
  (let [[st _] (prove/ask-items (items-st 0) 1 :batch {['app.cheap-test/t "dc"] {:agent :a1 :ms 1}})
        [st _] (prove/ask-items st 2 :turn {['app.slow-test/u "ds"] {:agent :a2 :ms 100000}})
        [st outs] (#'prove/grant (assoc st :workers 1))]
    (is (= [:a2 #{'app.slow-test/u}] ((juxt #(get-in % [:req :id]) #(get-in % [:req :names])) (first (batches outs))))
        "the free slot goes to the turn's item")
    (is (= 2.0 (prove/item-value (get-in st [:items ['app.cheap-test/t "dc"]]) (:bid st))) "and the batch's item passed over aged a grant: its one waiter plus one, over its 1 ms")))

;; every wait ends: a batch whose run errored (a test child that died) answers each of its askers with that error, never silence
;; docs/merging.md#the-test-service: a share of the queue over the free workers: two items on two free workers are two grants
(deftest a-worker-pulls-one-item-at-a-time
  (let [[_ outs] (prove/ask-items (items-st 2) 1 :batch {['app.x-test/a "da"] {:agent :a1 :ms 5} ['app.x-test/b "db"] {:agent :a1 :ms 5}})]
    (is (= [1 1] (mapv #(count (get-in % [:req :names])) (batches outs))) "two workers, one item each, though both are app.x-test's")))

;; docs/merging.md#the-test-service: of the askers waiting, the one nearest done is served first (shortest remaining work first), so
;; ten askers finish one by one rather than all late together; an older asker with more left waits, though it came first
(deftest the-asker-nearest-done-is-served-first
  (let [it {:agent :a1 :ms 10}
        [st _] (prove/ask-items (items-st 0) 1 :batch {['app.b-test/x "dx"] it ['app.b-test/y "dy"] it ['app.b-test/z "dz"] it})
        [st _] (prove/ask-items st 2 :batch {['app.a-test/t "dt"] (assoc it :agent :a2)})
        [_ o] (#'prove/grant (assoc st :workers 1))]
    (is (= ['#{app.a-test/t}] (mapv #(get-in % [:req :names]) (batches o))) "the one-item asker first, though asked second")))

;; docs/merging.md#the-test-service: an asker's new request for the same slot at another digest supersedes its run in flight: its
;; waiter is answered :seon.prove/superseded, what only it queued leaves the queue, and the new digest is planned
(deftest a-newer-ask-of-a-slot-supersedes-the-run-in-flight
  (let [[st o] (feed (items-st 0) [[:req {:rid 1 :branch :b :digest "d1" :agent :a1}]])
        [st _] (feed st [(answer-of (first (asks o :tests)) {:items {'app.x-test/t "dt"} :selected ['app.x-test/t]})])
        _ (is (seq (:items st)) "the first run's item is queued")
        [st o2] (feed st [[:req {:rid 2 :branch :b :digest "d2" :agent :a1}]])
        r1 (:r (first (filter #(and (= :prove-answer (:op %)) (= 1 (:rid %))) o2)))]
    (is (= :seon.prove/superseded (:seon.error/type r1)) (pr-str r1))
    (is (= {} (:items st)) "its item no one else waited on left the queue")
    (is (= 1 (count (filter #(get-in % [:req :plan?]) (asks o2 :tests)))) "and the new digest is planned")
    (is (empty? (filter #(= :problem (:op %)) o2)) "a superseded run is no platform problem")))

(deftest a-batch-that-died-answers-its-askers-with-the-error
  (let [[st o1] (prove/ask-items (items-st 1) 7 :batch {['app.x-test/t "d1"] {:agent :a1 :ms 5}})
        b (first (batches o1))
        [st v] (step/answered st (:tag b))
        died {:seon.error/type :seon.test/fork-refused :seon.error/message "the test child could not be forked"}
        [st o2] (prove/batch-ended st (parse-long (second v)) died)
        r (get (answers o2) 7)]
    (is (false? (:green r)))
    (is (seon.contract/error? (get-in r [:results 'app.x-test/t])) "the asker holds the typed error for the item")
    (is (= 1 (free st)))))

;; docs/merging.md#the-test-service: each asker is told an item's verdict as it lands, before it is answered, so a session's
;; bin/prove prints each test as it ends; an item with no verdict is told as :no-verdict, never left out
(deftest each-asker-is-told-a-verdict-as-it-lands
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

(defn queue-of
  "Grant's argument at n: the service's state with n items queued for one batch asker and one free worker. See
  docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map]]}
  [n]
  [(-> (items-st 1)
       (assoc :items (into {} (for [i (range n)] [[(symbol "app.q-test" (str "t" i)) (str "d" i)] {:agent :a1 :ms (inc (mod i 97)) :status :queued :waiters #{1} :at 0}]))
              :askers {1 {:prio :batch :left #{} :results {}}}))])

;; docs/merging.md#the-test-service: dispatch is one grant a free worker; what it costs per item queued
(deftest a-grant-costs-what-is-queued
  (seon.cost/proof
    {:of 'seon.prove/grant :input 'seon.prove-test/queue-of
     :n "items queued, one worker free"
     :work "one pass over the queued items for the most valuable (item-value, its age read from the grant it was queued at, and its waiters read only when an asker is a turn's), and its batch (batch-of): one worker free, its share is every queued item, so two passes more (count, take) and each item's status, its place in the batch and in the request written; with two askers or more, one pass summing each asker's work left (an update a waiter) (linear in reads and writes)"
     :growth :linear
     ;; measured 2026-11-11 at 100 queued: 6,261 ticks and 19.0 KB a grant of one item, where aging every item it passed over, by
     ;; a lazy filter and apply max-key, took 43.2k ticks and 355.9 KB. A grant of a batch (2026-11-12) writes each item it takes
     ;; (its status, its place in the batch and the request: ~1.5 KB) and walks the queue twice more (a filter's seq, then a
     ;; destructured seq of entries: ~1.5 KB), and the askers' work left (one map update a waiter, ~1 KB); measured 588 ticks and
     ;; 4.2 KB an item at 100
     :per-n {:ticks 500 :bytes 4000}
     :fixed {:ticks 2000 :bytes 2000}
     :sizes [100 300 1000]}))

;; ---- a checkout's process tests, check steps and build as items (notes test-system-as-a-flow-2026-11-10.md, step 4) ----

(defn- tree-run
  "A session's run of checkout /ck at tree T (agent a1 holds its branch), with slots workers, its plan answered with plan:
  [st outputs of the plan's answer, the plan ask]."
  [slots plan]
  (let [[st o] (feed (items-st slots) [[:req {:rid 1 :branch :tree-T :digest "dT" :agent :a1 :checkout "/ck" :tree "T"}]])
        ask (first (asks o :tests))
        [st o2] (feed st [(answer-of ask plan)])]
    [st o2 ask]))
(defn- fx-asks [outs] (filterv #(= :fx (get-in % [:req :op])) outs))
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
(deftest a-process-item-waits-for-its-trees-build-and-seed
  (let [[st o ask] (tree-run 4 {:items {} :process {'a.p-test/t "dp"} :build {:tree "T" :needed? true}})
        [b] (fx-asks o)]
    (is (true? (get-in ask [:req :process?])) "a checkout's run asks its agent for its process items")
    (is (= [{:kind :build :tree "T" :checkout "/ck" :name :build :digest "T" :binary? false :bound nil}] (mapv #(get-in % [:req :req]) (fx-asks o)))
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

(deftest a-trees-green-build-and-seed-are-made-once-for-its-checkout
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

(deftest a-seed-that-kept-the-tree-only-is-not-a-seed-of-the-stores
  ;; a run of a laws step seeds the tree only (no check store, no process tests' store); a later run of that tree with a
  ;; process test needs both stores, so the checkout's green seed of the first kind is no seed of the second (:made)
  (let [[st o] (seeded-run 4 {:items {} :steps {"laws" {:digest "dl" :binary? false}} :build {:tree "T" :needed? false}})
        [st _] (feed st [(answer-of (first (fx-asks o)) {:value {:pass 1 :fail 0 :error 0 :ms 1}})])
        [st o2] (feed st [[:req {:rid 2 :branch :tree-T :digest "dT" :agent :a1 :checkout "/ck" :tree "T" :names '#{a.p-test}}]])
        [_ o3] (feed st [(answer-of (first (asks o2 :tests)) {:items {} :process {'a.p-test/t "dp"} :build {:tree "T" :needed? false}})])]
    (is (= [[:seed true]] (mapv #(vector (get-in % [:req :req :kind]) (get-in % [:req :req :binary?])) (fx-asks o3)))
        "the process test's run seeds the stores first")))

(deftest a-step-about-the-machine-runs-with-nothing-beside-it
  ;; Optimization's e2e of step 4: scale (it measures what root's agents cost the machine's memory) was granted beside 29 other
  ;; steps and 7 of its agents answered no verdict; bin/check runs it alone (seon.proofs/machine-steps)
  (let [[st o] (seeded-run 4 {:items {} :steps {"scale" {:digest "ds" :binary? false :alone? true} "laws" {:digest "dl" :binary? false}
                                              "fx" {:digest "df" :binary? false}}
                            :build {:tree "T" :needed? false}})
        names (fn [outs] (mapv #(get-in % [:req :req :name]) (fx-asks outs)))]
    (is (= ["scale"] (names o)) "nothing running: scale, the most valuable, is granted, and nothing beside it though three workers are free")
    (let [[st o1] (feed st [(answer-of (first (fx-asks o)) {:value {:pass 1 :fail 0 :error 0 :ms 10}})])]
      (is (= #{"laws" "fx"} (set (names o1))) "it ended: the others at once")))
  (let [[st o] (seeded-run 4 {:items {} :steps {"laws" {:digest "dl" :binary? false :ms 1} "scale" {:digest "ds" :binary? false :alone? true}}
                            :build {:tree "T" :needed? false}})
        names (fn [outs] (mapv #(get-in % [:req :req :name]) (fx-asks outs)))
        ran (names o)]
    (is (= 1 (count ran)) "one of the two at a time")
    (let [[_ o1] (feed st [(answer-of (first (fx-asks o)) {:value {:pass 1 :fail 0 :error 0 :ms 10}})])]
      (is (= 1 (count (names o1))) "and the other after it"))))

;; every wait ends: a red build is every item's that needs it, and none of them runs on a binary that was never built
(deftest a-red-build-answers-what-needs-it-with-its-error
  (let [[st o] (tree-run 4 {:items {} :process {'a.p-test/t "dp"} :steps {"smoke" {:digest "ds" :binary? true :bound 5000}} :build {:tree "T" :needed? true}})
        [b] (fx-asks o)
        [_ o2] (feed st [(answer-of b {:value {:pass 0 :fail 1 :error 0 :failures [{:type :fail :message "bin/build exited 1"}]}})])
        [r] (run-answers o2)]
    (is (= 1 (count (fx-asks o))) "only the build was granted")
    (is (= [] (fx-asks o2)) "and after it, nothing")
    (is (= :seon.prove/build-red (:seon.error/type r)) (pr-str r))))

;; docs/merging.md#the-test-service: one bound, the workers, for every kind: a build holds a worker while it runs
(deftest one-bound-covers-builds-and-tests
  (let [[st o] (tree-run 1 {:items {'a.b-test/t "dt"} :process {'a.p-test/t "dp"} :build {:tree "T" :needed? true}})
        granted (fn [outs] (+ (count (fx-asks outs)) (count (remove #(get-in % [:req :plan?]) (asks outs :tests)))))]
    (is (= 1 (granted o)) "one worker: one item granted, of a build and a test queued")
    (let [first-ask (first (concat (fx-asks o) (asks o :tests)))
          r (if (seq (fx-asks o)) {:value {:pass 1 :fail 0 :error 0}} {:pass 1 :fail 0 :error 0 :reports [{:type :pass :var "#'a.b-test/t"}]})
          [_ o2] (feed st [(answer-of first-ask r)])]
      (is (= 1 (granted o2)) "it ended: the next one alone"))))

;; docs/merging.md#the-test-service: a fork's start and what its first test makes (a process test's root) are paid once a
;; batch: the queue goes to the free workers in shares, never one test a fork (measured 2026-11-12: one system test 20 s in a
;; fresh fork, each after it 0.5 s)
(deftest a-grant-is-a-share-of-the-queue-for-one-fork
  (let [items (into {} (for [i (range 8)] [(symbol "a.b-test" (str "t" i)) (str "d" i)]))
        [_ o] (tree-run 4 {:items items :build {:tree "T" :needed? false}})
        grants (mapv #(get-in % [:req :items]) (remove #(get-in % [:req :plan?]) (asks o :tests)))]
    (is (= [2 2 2 2] (mapv count grants)) (str "8 tests on 4 workers: 4 forks of 2: " (pr-str grants)))
    (is (= items (apply merge grants)) "every test granted once")))

;; a step past its bound is a signal (docs/clocks.md#time-decides-the-owners-exceptions), never a verdict: its asker is answered
;; on the rest, and the answer names it
(deftest a-step-that-overran-is-a-signal-not-a-red
  (let [[st o] (seeded-run 4 {:items {'a.b-test/t "dt"} :steps {"laws" {:digest "dl" :binary? false :bound 100}} :build {:tree "T" :needed? false}})
        [s] (fx-asks o)
        [t] (remove #(get-in % [:req :plan?]) (asks o :tests))
        [st o2] (feed st [(answer-of s {:value {:signal :overran :ms 150}})])
        [_ o3] (feed st [(answer-of t {:pass 1 :fail 0 :error 0 :reports [{:type :pass :var "#'a.b-test/t"}]})])
        [r] (run-answers o3)]
    (is (= {:kind :step :name "laws" :bound 100 :binary? false} (select-keys (get-in s [:req :req]) [:kind :name :bound :binary?])) "no build: none is needed")
    (is (empty? (run-answers o2)) "the signal answers no one")
    (is (= [:signal] (mapv :status (filter #(= :prove-verdict (:op %)) o2))) "it streams as a signal")
    (is (= [[] 1 0] ((juxt :red :pass :fail) r)) (pr-str r))
    (is (= [{:test "laws" :digest "dl" :kind :step :signal :overran :ms 150}] (:signals r)))))

;; docs/merging.md#the-test-service: a step on the binary needs its tree's seeded store, made once a tree after the build; a red seed is its steps'
(deftest a-binary-step-waits-for-the-seed-and-a-red-seed-is-its-error
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
(deftest a-red-step-is-named-red-and-never-remembered
  (let [[st o] (seeded-run 4 {:items {} :steps {"laws" {:digest "dl" :binary? false :bound 100}} :build {:tree "T" :needed? false}})
        [s] (fx-asks o)
        [st o2] (feed st [(answer-of s {:value {:pass 0 :fail 1 :error 0 :failures [{:type :fail :message "measures FAILED"}]}})])
        [r] (run-answers o2)
        [_ o3] (feed st [[:req {:rid 2 :branch :tree-T :digest "dT" :agent :a1 :checkout "/ck" :tree "T"}]])]
    (is (= [["laws"] 0 1] ((juxt :red :pass :fail) r)) (pr-str r))
    (is (empty? (run-answers o3)) "asked again, it is not answered from a remembered green")
    (is (= 1 (count (filter #(get-in % [:req :plan?]) (asks o3 :tests)))) "it is planned again")))

;; ---- admission: three optional bounds, any combination (docs/merging.md#the-test-service) --------------------------------

(defn- five-asked
  "The items granted when one asker asks five items of a service under admission a (seon.prove/admission's map)."
  [a]
  (let [[_ outs] (prove/ask-items (merge (proc0) a) 1 :batch
                                  (into {} (for [i (range 5)] [[(symbol "app.a-test" (str "t" i)) (str "d" i)] {:agent :a1 :ms 5}])))]
    (count (batches outs))))

;; docs/merging.md#the-test-service: a count bound grants that many of five
(deftest max-workers-grants-that-many
  (is (= 2 (five-asked (prove/admission {:max-workers 2 :cores 18})))))

;; docs/merging.md#the-test-service: a share of the cores is a count of them, the least count set holds
(deftest max-cores-is-a-share-of-the-cores
  (is (= 2 (five-asked (prove/admission {:max-cores 0.5 :cores 4}))) "half of four cores: two")
  (is (= 3 (five-asked (prove/admission {:max-cores 0.2 :max-workers 4 :cores 18}))) "a fifth of eighteen, under max-workers")
  (is (= {:workers 1} (prove/admission {:max-cores 0.1 :cores 4})) "at least one"))

;; docs/clocks.md#the-machines-load-admits-work: over max-load nothing more is granted while an item runs; with nothing
;; running one item is granted anyway, so the queue never stalls with nothing to wake it
(deftest max-load-admits-only-under-it-but-never-stalls
  (let [reads (atom 0)
        st0 (merge (proc0) (prove/admission {:max-workers 4 :max-load 10.0 :cores 18}))
        it {:agent :a1 :ms 5}]
    (with-redefs [prove/machine-load (fn [] (swap! reads inc) 30.0)]
      (let [[st o1] (prove/ask-items st0 1 :batch {['app.l-test/a "da"] it ['app.l-test/b "db"] it ['app.l-test/c "dc"] it})
            _ (is (= 1 (count (batches o1))) "nothing running: one granted though the load is over")
            _ (is (= 30.0 (:load (#'prove/state-view st))) "the reading is in what the service reports")
            [st v] (step/answered st (:tag (first (batches o1))))
            [st o2] (prove/batch-ended st (parse-long (second v)) (green-of (first (get-in (first (batches o1)) [:req :names]))))]
        (is (= 1 (count (batches o2))) "it ended: nothing running again, one more granted")
        (let [[_ o3] (prove/ask-items st 2 :batch {['app.l-test/d "dd"] it})]
          (is (= [] (batches o3)) "one running and the load over: nothing granted"))))
    (with-redefs [prove/machine-load (fn [] 2.0)]
      (let [[_ o] (prove/ask-items st0 1 :batch {['app.l-test/a "da"] it ['app.l-test/b "db"] it ['app.l-test/c "dc"] it})]
        (is (= 3 (count (batches o))) "the load under it: as many as the workers allow")))))

;; docs/merging.md#the-test-service: with max-cores and max-load unset, the shipped max-workers, the machine's cores
;; (#seon/cores), is the bound; with no count set the cores are too
(deftest the-shipped-bound-holds-when-the-others-are-unset
  ;; the service's init takes the admission of the config in force (seon.prove/config-admission), whatever machine runs it
  (let [init (prove/prove-proc {:conn nil})
        st (proc0)
        [_ outs] (prove/ask-items st 1 :batch (into {} (for [i (range 10)] [[(symbol "app.s-test" (str "t" i)) (str "d" i)] {:agent :a1 :ms 5}])))]
    (is (= (prove/config-admission) (select-keys init [:workers :max-load])))
    (is (= (seon.config/value :seon.config.machine/cores) (:workers st)) "the shipped max-workers: the machine's cores")
    (is (= (min 10 (:workers st)) (count (batches outs))) "ten items over that many workers, a share each"))
  (is (= {:workers 4} (prove/admission {:cores 4}))))
