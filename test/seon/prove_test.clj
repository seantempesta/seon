(ns seon.prove-test
  "The test service (seon.prove): admission as a step-fn (one run per checkout, the latest request
  of a branch wins, a running key is joined, every waiter answered once); a run writes nothing to the
  store (its agent caches its verdicts by digest: seon.results-test).
  Run: bin/test seon.prove-test"
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d]
            [seon.test-store :as ts] [seon.agent-test :as at] [clojure.edn] [seon.prove :as prove] [seon.agent] [seon.cost] [seon.proofs :as proofs] [seon.program]))

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
  "Drive the run that st (a run just started by `outs`) is on, to its end: the runner answers a
  tests request with answer, the write with ok. Answers [st all-outputs]."
  [st outs answer]
  (loop [st st all outs seen 0]
    (let [pending (drop seen all)]
      (if-let [m (first (filter :req pending))]
        (let [op (get-in m [:req :op])
              r (case op :spawn {:id :p1} :tests answer :write {:ok true :t 6} :kill {:killed :p1})
              [st o] (feed st [(answer-of m r)])]
          (recur st (into all o) (inc (.indexOf all m))))
        [st all]))))

(deftest a-running-key-is-joined-and-every-waiter-is-answered-once
  (let [[st outs] (feed (prove/prove-proc {}) [(req 1 :main 5) (req 2 :main 5) (req 3 :main 5)])]
    (is (= 1 (count (asks outs :spawn))) "three askers of one key: one run")
    (let [[st all] (run-through st outs test-run-answer)]
      (is (= [1 2 3] (waiters-answered all)) "each answered, once")
      (is (= 1 (count (asks all :tests))))
      (is (nil? (:running st)))
      (testing "the key again after its run: answered at once, nothing runs"
        (let [[_ o] (feed st [(req 4 :main 5)])]
          (is (= [4] (waiters-answered o)))
          (is (empty? (filter :req o))))))))

(deftest a-commit-starts-no-run-of-main-unless-the-config-says-so
  ;; task seon.proofs/main-is-proved-in-a-fork: the writer's commit notice records main's t and, with
  ;; :seon.config.proofs/prove-main-on-commit (off by default: the run of all of main's tests dies
  ;; in its child), a commit that moves the program starts a run of main (the process test below,
  ;; main-is-proved-at-boot-with-the-config-on-nobody-asking). Off, neither a data commit nor a program commit
  ;; runs anything, and a session's request for main runs as ever, at the t the notice recorded
  (let [db (ts/test-db)
        dg (seon.program/program-digest db)
        [st o] (feed (prove/prove-proc {}) [[:main {:t 5 :ops [[:db/add 900 :task/name "x" 5 true]] :db db}]
                                            [:main {:t 6 :ops [[:db/add 901 :form/src "(defn f [] 1)" 6 true]] :db db}]])]
    (is (empty? o) "no run from either commit")
    (is (= 6 (:main-t st)))
    (let [[st o2] (feed st [[:req {:rid 7 :branch :main :digest dg :mode :main}]])
          [_ all] (run-through st o2 test-run-answer)]
      (is (= 1 (count (asks o2 :spawn))) "a session's request runs main")
      (is (= [7] (waiters-answered all)) "and is answered once, at its end")
      (is (= 0 (count (asks all :write))) "nothing written to the store: its agent cached its verdicts"))))

(deftest an-answer-whose-child-died-is-never-remembered
  ;; THE PLAN 0.7 (root, 2026-10-27): a judge's run whose test child died answered every later ask
  ;; of its key from memory, so the held task's next run (seon_done again, same branch, same
  ;; program) got the same death and never ran its proof. A death is no verdict: the next ask runs
  (let [died {:pass 0 :fail 0 :error 1 :reports [{:type :error :var "#'a.b-test/t" :exit {:exit 1} :message "test process died"}] :digests {} :ms 5}
        [st outs] (feed (prove/prove-proc {}) [(req 1 :main 5)])
        [st all] (run-through st outs died)
        _ (is (= [1] (waiters-answered all)) "its waiter is answered the death")
        [_ o] (feed st [(req 2 :main 5)])]
    (is (empty? (waiters-answered o)) "asked again: not answered from memory")
    (is (= 1 (count (asks o :spawn))) "it runs again")))

(deftest a-run-is-keyed-by-what-it-reads
  ;; seon.root/prove! and seon.agent/prove! name the digest of what the run reads: a branch's tx
  ;; moves with any write, the run's own facts, its prove agent's end and the eval that asked among them
  (let [main (fn [rid t digest] [:req {:rid rid :branch :main :t t :digest digest}])
        [st outs] (feed (prove/prove-proc {}) [(main 1 5 "d0")])
        [st _] (run-through st outs test-run-answer)]
    (testing "main at a later tx, nothing it reads changed: the ended run's answer, nothing runs"
      (let [[_ o] (feed st [(main 2 9 "d0")])]
        (is (= [2] (waiters-answered o)))
        (is (empty? (filter :req o)))))
    (testing "a change to the code or the tests moves the digest: a new run"
      (let [[_ o] (feed st [(main 3 9 "d1")])]
        (is (empty? (waiters-answered o)))
        (is (= 1 (count (asks o :spawn))))))))

(deftest a-session-and-an-agents-turn-for-one-digest-are-one-run
  ;; the session's seon_prove for agent a3's branch at tx 7, and a3's turn asking for the same
  (let [[st outs] (feed (prove/prove-proc {}) [(req 10 :agent-3 7 :a3) (req 11 :agent-3 7 :a3)])]
    (is (= 1 (count (asks outs :tests))) "one tests request, to the agent that holds the branch")
    (is (= {:op :tests :id :a3 :mode :judge} (:req (first (asks outs :tests)))))
    (is (empty? (asks outs :spawn)) "the agent runs its own branch: no prove agent")
    (let [[_ all] (run-through st outs test-run-answer)]
      (is (= [10 11] (waiters-answered all)))
      (is (= 1 (count (asks all :tests)))))))

(deftest a-burst-is-one-run-per-branch-and-the-latest-wins-without-starving-another-branch
  (let [[st outs] (feed (prove/prove-proc {}) [(req 1 :main 1)])
        ;; while main@1 runs: 200 requests on one branch (the burst), then one on another
        [st more] (feed st (concat (for [i (range 200)] (req (+ 100 i) :agent-3 (+ 10 i) :a3)) [(req 500 :agent-5 3 :a5)]))]
    (is (empty? (filter :req more)) "nothing starts while a run is in flight")
    (is (= #{[:agent-3 nil :judge] [:agent-5 nil :judge]} (set (keys (:next st)))) "one pending change per branch")
    (is (= 209 (get-in st [:next [:agent-3 nil :judge] :t])) "the latest of the burst")
    (is (= 200 (count (get-in st [:next [:agent-3 nil :judge] :waiters]))) "the replaced requests' askers follow the latest")
    (let [[st all] (run-through st outs test-run-answer)]
      (is (= 3 (count (asks all :tests))) "main, then agent-3 once, then agent-5 once: one run each")
      (is (= 202 (count (waiters-answered all))) "every asker answered")
      (is (= (count (distinct (waiters-answered all))) (count (waiters-answered all))) "once each")
      (is (nil? (:running st))))))

(deftest branches-take-turns
  (let [[st outs] (feed (prove/prove-proc {}) [(req 1 :b 1 :ab) (req 2 :a 1 :aa) (req 3 :c 1 :ac)])
        order (atom [])
        [st all] (loop [st st all outs seen 0]
                   (if-let [m (first (filter :req (drop seen all)))]
                     (let [op (get-in m [:req :op])
                           _ (when (= :tests op) (swap! order conj (get-in m [:req :id])))
                           r (case op :spawn {:id :p1} :tests test-run-answer :write {:ok true} :kill {:killed 1})
                           [st o] (feed st [(answer-of m r)])]
                       (recur st (into all o) (inc (.indexOf all m))))
                     [st all]))]
    (is (= [:ab :ac :aa] @order) "b ran first; then the next branch in name order after it (c), then a")))

(deftest a-failed-run-answers-its-waiters-with-the-error
  (let [[st outs] (feed (prove/prove-proc {}) [(req 1 :a 1 :aa) (req 2 :a 1 :aa)])
        err (seon.contract/error :seon.root/no-agent 'seon.root/run-tests! "no live agent" {})
        [st all] (run-through st outs err)]
    (is (= [1 2] (waiters-answered all)))
    (is (every? seon.contract/error? (map :r (filter #(= :prove-answer (:op %)) all))))))

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
        [st outs] (feed (prove/prove-proc {}) [request])
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
    (testing "the same day's request while it runs joins it; the effect answered, the full run is free and nothing is written"
      (let [[st2 o2] (feed st [request])
            [st3 o3] (feed st2 [(answer-of fx full-answer)])]
        (is (empty? (filter :req o2)) "no second run")
        (is (nil? (:full st3)) "the full run is free")
        (is (empty? (filter :req o3)) "no write: its shards cached their verdicts")
        (is (empty? (filter #(= :problem (:op %)) o3)) "a red test is not a platform defect")
        (testing "the day again: answered by its run, nothing runs"
          (is (empty? (filter :req (second (feed st3 [request]))))))))
    (testing "an effect that failed is a platform problem, and the full run is free for the next day"
      (let [err (seon.contract/error :seon.fx/io-error #'seon.prove/prove-proc "bin/worktree --at exited 1" {})
            [st5 o5] (feed st [(answer-of fx err)])]
        (is (nil? (:full st5)))
        (is (= 1 (count (filter #(= :problem (:op %)) o5))))))))

;; ---- the proof root asks for: one run for a session and an agent's turn ----------------------

(deftest a-session-and-an-agents-turn-for-one-digest-end-in-one-run
  ;; a real root and an agent of it. The agent's turn asks the service (seon.agent/prove!, what
  ;; seon.turn/observe calls) while a session asks for the same branch's head (seon.root/prove!,
  ;; the server's :prove op / seon_prove): the service runs it once, for both, and a session asking
  ;; again after the eval that asked recorded itself (the tx moved, nothing the run reads did) gets that run.
  (let [code #(str "(let [ap (c/submit! sys :" % " \"(select-keys (seon.agent/prove!) [:key :pass :fail :error])\" {:origin :outside})"
                   "      sp (c/prove! sys :" % ")"
                   "      s (select-keys @sp [:key :pass :fail :error])"
                   "      a @ap"
                   "      again (select-keys @(c/prove! sys :" % ") [:key])]"
                  "  (pr-str {:session s :agent (:text a) :again again}))")
        [r] (at/in-agent #(vector {:op :eval :agent "root" :code (code (:agent %))}))
        v (clojure.edn/read-string (:value r))]
    (is (map? v) (pr-str r))
    (is (re-find #"@[0-9a-f]+!turn$" (:key (:session v))) "the key is the agent's branch at the digest of its program")
    (is (= (:key (:session v)) (:key (:again v))) "asked again, nothing the run reads changed: the same run's answer")
    (is (re-find (re-pattern (java.util.regex.Pattern/quote (:key (:session v)))) (:agent v)) "the agent's turn got the same run: one run for the three askers")))

(deftest main-is-proved-at-boot-with-the-config-on-nobody-asking
  ;; task seon.proofs/main-is-proved-in-a-fork: with :seon.config.proofs/prove-main-on-commit a root
  ;; whose program holds seon.patterns-test proves main at its boot with no one asking (a prove
  ;; agent forked from the fork server's main, no tests loaded from the checkout); a session asking
  ;; for main's key after joins that run or reads its answer. Open (task seon.proofs/an-agent-forked-after-finds-mains-results): an agent
  ;; forked after does not find them at its digests, and a named run after a program commit does not
  ;; reuse them (measured: [false false], :reused 0)
  (let [booted (str "(let [r @(c/prove! sys nil) ts '[seon.patterns-test/a-backlog-is-the-unread-queue seon.patterns-test/workers-compete-for-one-shared-in-port]]"
                    ;; its verdicts are cached by digest (the answer carries only proofs and reds: seon.proofs/answer)
                    "  (pr-str {:key (:key r) :facts (vec (for [t ts :let [v (get (seon.fx/stored-results (select-keys (:digests r) ts)) (get (:digests r) t))] :when v] [t (if (seon.proofs/green? v) :pass :red)]))}))")
        [a] (at/in-agent [{:op :eval :agent "root" :code booted}]
                         {:dirs ["src" "test/seon/patterns_test.clj"] :own-root? true :sets [":seon.config.proofs/prove-main-on-commit=true"]})
        a' (some-> (:value a) clojure.edn/read-string)]
    (is (= '[[seon.patterns-test/a-backlog-is-the-unread-queue :pass] [seon.patterns-test/workers-compete-for-one-shared-in-port :pass]]
           (:facts a'))
        (str "main proved at its boot, nobody asking: " (pr-str a)))))

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

(deftest a-request-naming-other-tests-never-takes-over-a-pending-run
  (let [[st outs] (feed (prove/prove-proc {}) [(req 1 :main 1)])
        names (fn [rid ns] [:req {:rid rid :branch :main :t 2 :digest "d2" :names ns}])
        [st more] (feed st [(names 2 '#{a.x-test}) (names 3 '#{a.y-test}) (names 4 '#{a.x-test})])]
    (is (empty? (filter :req more)) "nothing starts")
    (is (= 2 (count (:next st))) "two slots pending: the namespaces named differ")
    (is (= #{2 4} (get-in st [:next [:main '#{a.x-test} :judge] :waiters])) "the same slot joins")
    (is (= #{3} (get-in st [:next [:main '#{a.y-test} :judge] :waiters])) "the other keeps its own")
    (let [[_ all] (run-through st outs test-run-answer)]
      (is (= 3 (count (asks all :spawn))) "three runs: main, x, y, each its own")
      (is (= [1 2 3 4] (waiters-answered all))))))

(deftest an-error-is-answered-recorded-and-never-remembered
  (let [err (seon.contract/error :seon.root/no-agent 'seon.root/run-tests! "no live agent" {})
        [st outs] (feed (prove/prove-proc {}) [(req 1 :agent-3 7 :a3)])
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
  ;; tests; root answers a request to an agent that ended :seon.root/ended (its producer knows
  ;; ended from never-was), the run's waiter is answered with it, and nothing is recorded as a
  ;; :platform problem: the agent's end is its run's record
  (let [ended (seon.contract/error :seon.root/ended 'seon.root/api-proc "agent a3 ended" {:agent :a3})
        [st outs] (feed (prove/prove-proc {}) [(req 1 :agent-3 7 :a3)])
        [st all] (run-through st outs ended)]
    (is (= [1] (waiters-answered all)) "its waiter is answered with the error")
    (is (not-any? #(= :problem (:op %)) all) "and no :platform problem is recorded")
    (is (empty? (:done st)) "nothing remembered")))

(deftest a-spawn-failure-and-a-prove-agent-that-dies-are-answered-and-recorded
  (let [[st outs] (feed (prove/prove-proc {}) [(req 1 :main 1) (req 2 :main 1)])
        spawn (first (asks outs :spawn))
        refused (seon.contract/error :seon.root/refused 'seon.root/spawn! "too many live agents" {})
        [st o] (feed st [(answer-of spawn refused)])]
    (is (= [1 2] (waiters-answered o)) "a spawn refused: both waiters answered the error")
    (is (some #(= :problem (:op %)) o))
    (is (nil? (:running st))))
  (let [[st outs] (feed (prove/prove-proc {}) [(req 1 :main 1)])
        [st o2] (feed st [(answer-of (first (asks outs :spawn)) {:id :p1})])
        died (seon.contract/error :seon.agent/died 'seon.root/api-proc "agent p1 died" {:agent :p1})
        [st o3] (feed st [(answer-of (first (asks o2 :tests)) died)])]
    (is (= [1] (waiters-answered o3)) "the prove agent died mid-run: its waiter is answered the error")
    (is (some #(and (= :problem (:op %)) (= :seon.agent/died (get-in % [:error :seon.error/type]))) o3))
    (is (= 1 (count (asks o3 :kill))) "and its agent is killed")
    (is (empty? (:done st)))))

(deftest a-stale-answer-is-counted
  (let [[st outs] (feed (prove/prove-proc {}) [(req 1 :main 1)])
        [st _] (feed st [[:answers {:tag 99 :r {}}]])]
    (is (= 1 (:stale st)))
    (is (= 1 (:stale-answers ((:ping-map-fn (prove/prove-proc)) st))))))

(defn- lcg
  "The next of a linear congruential sequence from x (a pure function: the test draws no randomness)."
  [x] (mod (+ (* x 6364136223846793005) 1442695040888963407) 9223372036854775807))

(defn- model-run
  "A service driven through steps pseudo-random events from seed: a request (a branch of main, b1
  or b2, a digest of three, an asker agent for a branch) or the api answering one of the asks still
  open (a success or an :seon.root/ended error, as an agent ending answers). Then every ask left is
  answered with a success, until none. Answers [final-state rids-asked answers-seen]."
  [seed steps]
  (loop [i 0 x seed st (prove/prove-proc {}) open [] asked [] answers []]
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
                    (seon.contract/error :seon.root/ended #'seon.prove/prove-proc "the agent ended" {:agent :a1})
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
  ;; a hang in a run's chain is read from what the service holds (seon.root keeps it for a stall's
  ;; evidence): the running key and stage, who waits, and the api's asks it has open
  (let [state-of (fn [outs] (:r (last (filter #(= :prove-state (:op %)) outs))))
        [st outs] (feed (prove/prove-proc {}) [(req 1 :main 5) (req 2 :main 5)])
        s1 (state-of outs)]
    (is (= {:running "main@d5" :stage :spawn :waiting 2 :next 0} (select-keys s1 [:running :stage :waiting :next])))
    (is (= [[:spawn "main@d5"]] (:open s1)) "the ask it waits on")
    (let [spawn (first (asks outs :spawn))
          [st o] (feed st [(answer-of spawn {:id :p1})])
          s2 (state-of o)]
      (is (= [:tests [[:tests "main@d5"]] :p1] [(:stage s2) (:open s2) (:runner s2)]) "the spawn answered: the tests ask is the one open")
      (testing "a message that changes nothing it holds reports nothing"
        (let [[_ o2] (feed st [(answer-of {:tag 9999} {:ok true})])]
          (is (empty? (filter #(= :prove-state (:op %)) o2))))))))

(deftest two-runs-on-one-checkout-each-end-with-their-own-result
  ;; two bin/prove runs at once on one checkout wrote the same target/prove (shard files, logs):
  ;; one run's start removed the other's shards and a plain run destroyed a --merge run's logs.
  ;; Each run writes under a directory of its own, named on its first line, and each ends with
  ;; its own complete log and plan
  (let [run (fn [] (clojure.java.shell/sh "bin/prove" "--tests" "seon.step-test"))
        pa (promise) pb (promise)
        _ (doto (Thread. #(deliver pa (run))) .start)
        _ (doto (Thread. #(deliver pb (run))) .start)
        rs [@pa @pb]
        dir (fn [r] (second (re-find #"prove: run \S+, logs in (\S+)" (:out r))))]
    (doseq [r rs]
      (is (zero? (:exit r)) (str (:out r) (:err r)))
      (is (some? (dir r)) "a run says where its logs are")
      (is (clojure.string/includes? (:out r) "PROVE OK")))
    (is (not= (dir (first rs)) (dir (second rs))) "each run has a directory of its own")
    (doseq [r rs :let [d (dir r)]]
      (is (clojure.string/includes? (slurp (str d "/prove.log")) "PROVE OK") (str "its own complete log: " d))
      (is (.isFile (java.io.File. (str d "/tests.edn"))) (str "and its own plan: " d)))))
