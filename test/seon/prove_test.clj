(ns seon.prove-test
  "The test service (seon.prove): admission as a step-fn (one run per checkout, the latest request
  of a branch wins, a running key is joined, every waiter answered once), the facts a run leaves
  (greens and reds by digest) and the :test-red class they make true and a later green closes.
  Run: bin/test seon.prove-test"
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d]
            [seon.test-store :as ts] [seon.agent-test :as at] [clojure.edn] [seon.prove :as prove] [seon.agent] [seon.cost] [seon.problems :as problems] [seon.proofs :as proofs]))

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
              r (case op :spawn {:id :p1} :eval {:handle 'result/e1 :text "{:handle result/e1, :value 1}"} :tests answer :write {:ok true :t 6} :kill {:killed :p1})
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

(deftest the-read-digest-moves-with-what-the-run-reads
  (let [in {:tree "t1" :env "e1"} dg (prove/read-digest in "p1")]
    (is (not= dg (prove/read-digest (assoc in :tree "t2") "p1")) "a file of the checkout changed (a test, a doc)")
    (is (not= dg (prove/read-digest (assoc in :env "e2") "p1")) "the config or a pin changed")
    (is (not= dg (prove/read-digest in "p2")) "main's program changed")
    (is (= dg (prove/read-digest (assoc in :data-refs {}) "p1")) "the same reads: the same digest")
    (is (= "p1" (prove/read-digest nil "p1")) "an agent's run: its branch's program")))

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
    (is (empty? more) "nothing starts while a run is in flight")
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
                           r (case op :spawn {:id :p1} :eval {:handle 'result/e1 :text "{:handle result/e1, :value nil}"} :tests test-run-answer :write {:ok true} :kill {:killed 1})
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

;; ---- the facts a run leaves, and the class they make true ------------------------------------

(deftest a-run-leaves-a-fact-per-digest-and-a-red-closes-with-a-later-green
  (let [db0 (ts/test-db)
        red {:pass 0 :fail 1 :error 0 :ms 7 :digests {'a.b-test/t "dred"}
             :reports [{:type :fail :var "#'a.b-test/t" :message "boom" :expected "1" :actual "2"}]}
        run {:key "main@7" :branch :main :t 7 :agent :a3}
        db1 (d/db-with db0 (prove/result-tx run red))
        reds #(get-in (problems/report %) [:classes :test-red :rows])]
    (testing "a red result is a fact keyed by its digest, and an open :test-red row naming the test, the branch, the tx and who asked"
      (is (= "dred" (:seon.proof/digest (d/pull db1 [:seon.proof/digest] [:seon.proof/digest "dred"]))))
      (is (= ['a.b-test/t] (mapv :test (reds db1))))
      (is (re-find #"a.b-test/t is fail at main t 7, asked by a3" (:message (first (reds db1))))))
    (testing "a red digest is no green: the test runs again"
      (is (= {} (proofs/stored-greens db1 ["dred"]))))
    (let [green (assoc red :pass 1 :fail 0 :digests {'a.b-test/t "dgreen"} :reports [{:type :pass :var "#'a.b-test/t"}])
          db2 (d/db-with db1 (prove/result-tx (assoc run :key "main@8" :t 8) green))]
      (testing "a later green at a new digest closes the red: nothing written, the row is gone, the red fact is kept"
        (is (= [] (reds db2)))
        (is (some? (d/pull db2 [:seon.proof/digest] [:seon.proof/digest "dred"]))))
      (testing "a digest that holds a green is never run again: the lookup finds it in the store"
        (is (= ["dgreen"] (keys (proofs/stored-greens db2 ["dgreen" "dred" "dnone"])))))
      (testing "the run is a fact too"
        (is (= 1 (:seon.proof.run/fail (d/pull db2 [:seon.proof.run/fail] [:seon.proof.run/id "main@7"]))))))))

;; ---- the proof root asks for: one run for a session and an agent's turn ----------------------

(deftest a-session-and-an-agents-turn-for-one-digest-end-in-one-run
  ;; a real root and agent a1. The agent's turn asks the service (seon.agent/prove!, what
  ;; seon.turn/observe calls) while a session asks for the same branch's head (seon.root/prove!,
  ;; the server's :prove op / seon_prove): the service runs it once, for both, and a session asking
  ;; again after the eval that asked recorded itself (the tx moved, nothing the run reads did) gets that run.
  (let [code (str "(let [ap (c/submit! sys :a1 \"(select-keys (seon.agent/prove!) [:key :pass :fail :error])\" {:origin :outside})"
                  "      sp (c/prove! sys :a1)"
                  "      s (select-keys @sp [:key :pass :fail :error])"
                  "      a @ap"
                  "      again (select-keys @(c/prove! sys :a1) [:key])"
                  "      runs (d/q '[:find [?id ...] :where [?r :seon.proof.run/id ?id]] @(:conn sys))]"
                  "  (pr-str {:session s :agent (:text a) :again again :runs (vec runs)}))")
        [r] (at/in-agent [{:op :eval :agent "root" :code code}])
        v (clojure.edn/read-string (:value r))]
    (is (map? v) (pr-str r))
    (is (re-find #"@[0-9a-f]+!turn$" (:key (:session v))) "the key is the agent's branch at the digest of its program")
    (is (= (:key (:session v)) (:key (:again v))) "asked again, nothing the run reads changed: the same run's answer")
    (is (re-find (re-pattern (java.util.regex.Pattern/quote (:key (:session v)))) (:agent v)) "the agent's turn got the same run")
    (is (= [(:key (:session v))] (:runs v)) "ONE run fact for the three askers")))

(deftest a-session-prove-of-main-runs-in-a-prove-agent-and-leaves-facts
  ;; main has no agent: the service spawns one on the tests branch, loads the checkout's tests there,
  ;; runs the namespace asked for, kills the agent, and records a fact per test digest on main.
  ;; The facts are read by a form of their own: a form reads the db as it began.
  (let [ask (str "(let [r @(c/prove! sys nil '#{seon.patterns-test})"
                 "      again @(c/prove! sys nil '#{seon.patterns-test})]"
                 "  (pr-str {:pass (:pass r) :fail (:fail r) :error (:error r) :ran (:ran r) :key (:key r) :again (:key again) :red (:red r)}))")
        read (str "(let [facts (d/q '[:find ?t ?s ?dg :where [?e :seon.proof/digest ?dg] [?e :seon.proof/test ?t] [?e :seon.proof/status ?s]] @(:conn sys))"
                  "      runs (d/q '[:find [?id ...] :where [?r :seon.proof.run/id ?id]] @(:conn sys))"
                  "      green (seon.proofs/stored-greens @(:conn sys) (map last facts))]"
                  "  (pr-str {:facts (vec (sort (map (juxt first second) facts))) :runs (vec runs) :green (count green)"
                  "           :latest (vec (sort (map first (d/q '[:find ?l :where [?e :seon.proof/latest ?l]] @(:conn sys)))))}))")
        [r1 r2] (at/in-agent [{:op :eval :agent "root" :code ask} {:op :eval :agent "root" :code read}])
        v (merge (clojure.edn/read-string (:value r1)) (clojure.edn/read-string (:value r2)))]
    (is (map? v) (pr-str r1 r2))
    (is (= [0 0 true []] ((juxt :fail :error :ran :red) v)))
    (is (pos? (:pass v)))
    (is (= (:key v) (:again v)) "asked again: the same run's answer, nothing runs")
    (is (= [(:key v)] (:runs v)) "one run fact")
    (is (= '[[seon.patterns-test/a-backlog-is-the-unread-queue :pass] [seon.patterns-test/workers-compete-for-one-shared-in-port :pass]] (:facts v))
        "a result fact per test, by digest")
    (is (= '["seon.patterns-test/a-backlog-is-the-unread-queue" "seon.patterns-test/workers-compete-for-one-shared-in-port"] (:latest v)) "and main's newest result per test")
    (is (= 2 (:green v)) "and each digest now holds a green: test-plan finds it in the store")))

(deftest a-run-naming-nothing-leaves-out-the-process-tests-and-a-run-naming-them-runs-them
  (let [plan {:selected '[a.b-test/plain a.p-test/proc a.p-test/other] :run '[a.b-test/plain a.p-test/proc a.p-test/other]
              :process '#{a.p-test/proc}}
        sel (fn [names proofs] (let [r (seon.agent/run-selection plan names proofs)] (select-keys r [:selected :run :skipped])))]
    (testing "naming nothing: the process test is skipped, and listed"
      (is (= '{:selected [a.b-test/plain a.p-test/other] :run [a.b-test/plain a.p-test/other] :skipped [a.p-test/proc]} (sel nil #{}))))
    (testing "naming its namespace: it runs"
      (is (= '{:selected [a.p-test/proc a.p-test/other] :run [a.p-test/proc a.p-test/other] :skipped []} (sel '#{a.p-test} #{}))))
    (testing "naming another namespace: it is not in the run"
      (is (= '{:selected [a.b-test/plain] :run [a.b-test/plain] :skipped []} (sel '#{a.b-test} #{}))))
    (testing "a task's proof that reaches a process is named by the task: it runs"
      (is (= '[a.b-test/plain a.p-test/proc a.p-test/other] (:run (sel nil '#{a.p-test/proc})))))
    (testing "the run fact lists what was skipped"
      (let [tx (prove/result-tx {:key "main@1" :branch :main :t 1} {:pass 1 :reports [] :deferred '[a.p-test/proc]})]
        (is (= '[a.p-test/proc] (:seon.proof.run/skipped-process (first tx))))))))

(deftest a-red-on-an-agents-branch-is-not-a-problem-of-main
  (let [db0 (ts/test-db)
        red {:pass 0 :fail 1 :error 0 :ms 7 :digests {'a.b-test/t "dagent"}
             :reports [{:type :fail :var "#'a.b-test/t" :message "boom" :expected "1" :actual "2"}]}
        db (d/db-with db0 (prove/result-tx {:key "agent-3@7" :branch :agent-3 :t 7 :agent :a3} red))]
    (is (some? (d/pull db [:seon.proof/digest] [:seon.proof/digest "dagent"])) "the result is kept by digest")
    (is (= [] (get-in (problems/report db) [:classes :test-red :rows])) "and is not main's problem")))

(defn reds-input
  "The cost proof's input at n: n tests with a newest result on main, a tenth of them red, each
  with nine older results in the history (kept by digest): the class must cost the tests."
  [n] (let [db0 (ts/test-db)
            tx (vec (mapcat (fn [i] (prove/result-tx {:key (str "main@" i) :branch :main :t i}
                                                      {:pass (if (zero? (mod i 10)) 0 1) :fail (if (zero? (mod i 10)) 1 0) :ms 1
                                                       :digests {(symbol "a.b-test" (str "t" i)) (str "d" i)}
                                                       :reports [{:type (if (zero? (mod i 10)) :fail :pass) :var (str "#'a.b-test/t" i)
                                                                  :message "m" :expected "1" :actual "2"}]}))
                            (range n)))]
        [(d/db-with db0 tx) {}]))

(deftest test-reds-follow-the-tests-not-the-results
  (seon.cost/proof {:of 'seon.problems/test-reds :input 'seon.prove-test/reds-input
                    :n "tests with a newest result on main, a tenth red"
                    :work "one datom per test (the latest attribute), a status read each, and a pull and a row per red one"
                    :growth :linear
                    :per-n {:ns 30000 :bytes 30000 :calls 1}
                    :fixed {:ns 100000 :bytes 100000 :calls 2}
                    :sizes [10 100 1000]}))

;; ---- admission details ----------------------------------------------------------------------

(deftest a-request-naming-other-tests-never-takes-over-a-pending-run
  (let [[st outs] (feed (prove/prove-proc {}) [(req 1 :main 1)])
        names (fn [rid ns] [:req {:rid rid :branch :main :t 2 :digest "d2" :names ns}])
        [st more] (feed st [(names 2 '#{a.x-test}) (names 3 '#{a.y-test}) (names 4 '#{a.x-test})])]
    (is (empty? more))
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
        [st o] (feed st [(answer-of (first (asks outs :spawn)) {:id :p1})])
        [st o2] (feed st [(answer-of (first (asks o :eval)) {:handle 'result/e1 :text "{:handle result/e1, :value nil}"})])
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
