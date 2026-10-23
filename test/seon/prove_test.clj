(ns seon.prove-test
  "The test service (seon.prove): admission as a step-fn (one run per checkout, the latest request
  of a branch wins, a running key is joined, every waiter answered once), the facts a run leaves
  (greens and reds by digest) and the :test-red class they make true and a later green closes.
  Run: bin/test seon.prove-test"
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d]
            [seon.test-store :as ts] [seon.agent-test :as at] [clojure.edn] [seon.prove :as prove] [seon.problems :as problems] [seon.proofs :as proofs]))

(defn- feed
  "State st after the messages ([in m] ...) in order, with every output it made (the :to-api messages)."
  [st msgs]
  (reduce (fn [[st outs] [in m]] (let [[st o] (prove/prove-proc st in m)] [st (into outs (:to-api o))])) [st []] msgs))

(defn- req [rid branch t & [agent]] [:req (cond-> {:rid rid :branch branch :t t} agent (assoc :agent agent))])
(defn- asks [outs op] (filterv #(= op (get-in % [:req :op])) outs))
(defn- answer-of [m r] [:answers {:tag (:tag m) :r r}])
(defn- waiters-answered [outs] (sort (map :rid (filter #(= :prove-answer (:op %)) outs))))

(def ^:private run-answer {:pass 1 :fail 0 :error 0 :reports [{:type :pass :var "#'a.b-test/t"}] :digests {'a.b-test/t "d1"} :ms 5})

(defn- run-through
  "Drive the run that st (a run just started by `outs`) is on, to its end: the runner answers a
  tests request with answer, the write with ok. Answers [st all-outputs]."
  [st outs answer]
  (loop [st st all outs seen 0]
    (let [pending (drop seen all)]
      (if-let [m (first (filter :req pending))]
        (let [op (get-in m [:req :op])
              r (case op :spawn {:id :p1} :eval [{:tag :ret :printed "1"}] :tests answer :write {:ok true :t 6} :kill {:killed :p1})
              [st o] (feed st [(answer-of m r)])]
          (recur st (into all o) (inc (.indexOf all m))))
        [st all]))))

(deftest a-running-key-is-joined-and-every-waiter-is-answered-once
  (let [[st outs] (feed (prove/prove-proc {}) [(req 1 :main 5) (req 2 :main 5) (req 3 :main 5)])]
    (is (= 1 (count (asks outs :spawn))) "three askers of one key: one run")
    (let [[st all] (run-through st outs run-answer)]
      (is (= [1 2 3] (waiters-answered all)) "each answered, once")
      (is (= 1 (count (asks all :tests))))
      (is (nil? (:running st)))
      (testing "the key again after its run: answered at once, nothing runs"
        (let [[_ o] (feed st [(req 4 :main 5)])]
          (is (= [4] (waiters-answered o)))
          (is (empty? (filter :req o)))))
      (testing "main at the tx the run's own results were written at is the same run, not a new one"
        (let [[_ o] (feed st [(req 5 :main 6)])]
          (is (= [5] (waiters-answered o)))
          (is (empty? (filter :req o))))))))

(deftest a-session-and-an-agents-turn-for-one-digest-are-one-run
  ;; the session's seon_prove for agent a3's branch at tx 7, and a3's turn asking for the same
  (let [[st outs] (feed (prove/prove-proc {}) [(req 10 :agent-3 7 :a3) (req 11 :agent-3 7 :a3)])]
    (is (= 1 (count (asks outs :tests))) "one tests request, to the agent that holds the branch")
    (is (= {:op :tests :id :a3} (:req (first (asks outs :tests)))))
    (is (empty? (asks outs :spawn)) "the agent runs its own branch: no prove agent")
    (let [[_ all] (run-through st outs run-answer)]
      (is (= [10 11] (waiters-answered all)))
      (is (= 1 (count (asks all :tests)))))))

(deftest a-burst-is-one-run-per-branch-and-the-latest-wins-without-starving-another-branch
  (let [[st outs] (feed (prove/prove-proc {}) [(req 1 :main 1)])
        ;; while main@1 runs: 200 requests on one branch (the burst), then one on another
        [st more] (feed st (concat (for [i (range 200)] (req (+ 100 i) :agent-3 (+ 10 i) :a3)) [(req 500 :agent-5 3 :a5)]))]
    (is (empty? more) "nothing starts while a run is in flight")
    (is (= #{:agent-3 :agent-5} (set (keys (:next st)))) "one pending change per branch")
    (is (= 209 (get-in st [:next :agent-3 :t])) "the latest of the burst")
    (is (= 200 (count (get-in st [:next :agent-3 :waiters]))) "the replaced requests' askers follow the latest")
    (let [[st all] (run-through st outs run-answer)]
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
                           r (case op :spawn {:id :p1} :eval [{:tag :ret}] :tests run-answer :write {:ok true} :kill {:killed 1})
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
        run {:key "agent-3@7" :branch :agent-3 :t 7 :agent :a3}
        db1 (d/db-with db0 (prove/result-tx run red))
        reds #(get-in (problems/report %) [:classes :test-red :rows])]
    (testing "a red result is a fact keyed by its digest, and an open :test-red row naming the test, the branch, the tx and who asked"
      (is (= "dred" (:seon.proof/digest (d/pull db1 [:seon.proof/digest] [:seon.proof/digest "dred"]))))
      (is (= ['a.b-test/t] (mapv :test (reds db1))))
      (is (re-find #"a.b-test/t is fail at agent-3 t 7, asked by a3" (:message (first (reds db1))))))
    (testing "a red digest is no green: the test runs again"
      (is (= {} (proofs/stored-greens db1 ["dred"]))))
    (let [green (assoc red :pass 1 :fail 0 :digests {'a.b-test/t "dgreen"} :reports [{:type :pass :var "#'a.b-test/t"}])
          db2 (d/db-with db1 (prove/result-tx (assoc run :key "agent-3@8" :t 8) green))]
      (testing "a later green at a new digest closes the red: nothing written, the row is gone, the red fact is kept"
        (is (= [] (reds db2)))
        (is (some? (d/pull db2 [:seon.proof/digest] [:seon.proof/digest "dred"]))))
      (testing "a digest that holds a green is never run again: the lookup finds it in the store"
        (is (= ["dgreen"] (keys (proofs/stored-greens db2 ["dgreen" "dred" "dnone"])))))
      (testing "the run is a fact too"
        (is (= 1 (:seon.proof.run/fail (d/pull db2 [:seon.proof.run/fail] [:seon.proof.run/id "agent-3@7"]))))))))

;; ---- the proof root asks for: one run for a session and an agent's turn ----------------------

(deftest a-session-and-an-agents-turn-for-one-digest-end-in-one-run
  ;; a real root and agent a1. The agent's turn asks the service (seon.agent/prove!, what
  ;; seon.turn/observe calls) while a session asks for the same branch at the same tx
  ;; (seon.root/prove!, the server's :prove op / seon_prove): the service runs it once, for both.
  (let [code (str "(let [ap (c/submit! sys :a1 \"(select-keys (seon.agent/prove!) [:key :pass :fail :error])\" {:origin :outside})"
                  "      sp (c/prove! sys :a1)"
                  "      s (select-keys @sp [:key :pass :fail :error])"
                  "      a @ap"
                  "      again (select-keys @(c/prove! sys :a1) [:key])"
                  "      runs (d/q '[:find [?id ...] :where [?r :seon.proof.run/id ?id]] @(:conn sys))]"
                  "  (pr-str {:session s :agent (:printed (first a)) :again again :runs (vec runs)}))")
        [r] (at/in-agent [{:op :eval :agent "root" :code code}])
        v (clojure.edn/read-string (clojure.edn/read-string (:printed r)))]
    (is (map? v) (pr-str r))
    (is (re-find #"@\d+$" (:key (:session v))) "the key is the agent's branch at its tx")
    (is (= (:key (:session v)) (:key (:again v))) "asked again at the same tx: the same run's answer")
    (is (re-find (re-pattern (java.util.regex.Pattern/quote (:key (:session v)))) (:agent v)) "the agent's turn got the same run")
    (is (= [(:key (:session v))] (:runs v)) "ONE run fact for the three askers")))

(deftest a-session-prove-of-main-runs-in-a-prove-agent-and-leaves-facts
  ;; main has no agent: the service spawns one on the tests branch, loads the checkout's tests there,
  ;; runs the namespace asked for, kills the agent, and records a fact per test digest on main
  (let [code (str "(let [r @(c/prove! sys nil '#{seon.patterns-test})"
                  "      again @(c/prove! sys nil '#{seon.patterns-test})"
                  "      facts (d/q '[:find ?t ?s ?dg :where [?e :seon.proof/digest ?dg] [?e :seon.proof/test ?t] [?e :seon.proof/status ?s]] @(:conn sys))"
                  "      runs (d/q '[:find [?id ...] :where [?r :seon.proof.run/id ?id]] @(:conn sys))"
                  "      green (seon.proofs/stored-greens @(:conn sys) (map last facts))"
                  "      statuses (map :status (vals (:agents (c/state sys))))]"
                  "  (pr-str {:pass (:pass r) :fail (:fail r) :error (:error r) :ran (:ran r) :key (:key r) :again (:key again) :red (:red r)"
                  "           :facts (vec (sort (map (juxt first second) facts))) :runs (vec runs) :green (count green) :prove-agent-gone (not-any? #{:busy} statuses)}))")
        [r] (at/in-agent [{:op :eval :agent "root" :code code}])
        v (clojure.edn/read-string (clojure.edn/read-string (:printed r)))]
    (is (map? v) (pr-str r))
    (is (= [0 0 true []] ((juxt :fail :error :ran :red) v)))
    (is (pos? (:pass v)))
    (is (= (:key v) (:again v)) "asked again: the same run's answer, nothing runs")
    (is (= [(:key v)] (:runs v)) "one run fact")
    (is (= '[[seon.patterns-test/a-backlog-is-the-unread-queue :pass] [seon.patterns-test/workers-compete-for-one-shared-in-port :pass]] (:facts v))
        "a result fact per test, by digest")
    (is (= 2 (:green v)) "and each digest now holds a green: test-plan finds it in the store")))
