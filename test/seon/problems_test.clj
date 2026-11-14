(ns seon.problems-test
  "Session D2: every class of problem arrives as a typed fact written by its observer, and
  seon.problems/report lists them by class at a cost that follows the problems, not the
  program. Run: bin/test seon.problems-test"
  (:require [seon.test-store :as ts] [my.test :as t] [seon.profile :as profile] [clojure.test :refer [deftest is testing]] [clojure.edn :as edn]
            [datahike.api :as d] [clojure.core.async :as a]
            [seon.contract :as contract] [seon.db :as pdb] [seon.error :as err] [seon.agent :as agent]
            [seon.repl :as repl] [seon.proofs :as proofs] [seon.system :as root] [seon.agent-proc :as ap]
            [seon.reconcile :as rec] [seon.sock :as sock]
            [seon.render :as render] [konserve.jolt.fasl] [clojure.set] [clojure.string :as str] [seon.source] [seon.fx :as fx] [seon.model :as model] [clojure.data.json] [seon.problems :as problems] [seon.task] [seon.template] [seon.cost :as cost] [seon.doc] [seon.edn :as sedn] [my.root] [clojure.java.shell :as sh]))

(defn- world
  "The world these tests run on: my.test's, keeping history, since stale sections are read from d/history
  (seon.problems/stale-sections), which a db without temporal indexes refuses (docs/testing.md#testing-in-the-world)."
  [] (t/world {:history? true}))

(defn- invalid [op v]
  (contract/error :seon.instrument/contract-error op (str op " invalid-input: its arguments do not satisfy its contract")
                  {:seon.error/member :malli.core/invalid-input
                   :seon.error/problems [{:path [0] :in [0] :expected :int :value {:seon.print/code (pr-str v)}}]}))

;; ---- the signature and the fold (ported from R4's seon.error-test) ----------------------

(deftest ^{:seon.test/estimate {:ticks 500000}} one-cause-one-signature
  (is (= (err/signature (invalid 'app/f nil)) (err/signature (invalid 'app/f 42))) "values never enter it")
  (is (not= (err/signature (invalid 'app/f nil)) (err/signature (invalid 'app/g nil))) "another producer, another signature")
  (is (= (err/signature {:seon.error/type :x/y :seon.error/message "slot 17 of 64"})
         (err/signature {:seon.error/type :x/y :seon.error/message "slot 3 of 64"})) "digits are normalised")
  (is (not= (err/signature {:seon.error/type :seon.agent/died :seon.error/message "died" :exit {:signal 9}})
            (err/signature {:seon.error/type :seon.agent/died :seon.error/message "died" :exit {:signal 11}}))
      "an exit status names a different cause"))

(defn- occ [e agent ms] (err/occurrence e :eval {:agent agent :task "t/a" :form "(f 1)"} ms))

(deftest ^{:seon.test/estimate {:ticks 10000000}} a-storm-is-one-write-per-window
  (ts/test-branch! (fn [_ conn] (let [f (reduce err/fold {} (concat (for [i (range 1000)] (occ (invalid 'app/f i) (if (even? i) :a1 :a2) (+ 1000 i)))
                                     (for [i (range 10)] (occ (invalid 'app/g i) :a3 (+ 5000 i)))))
        [f tx] (err/flush-tx f @conn)
        s (err/signature (invalid 'app/f 0))]
    (is (= 2 (count tx)) "1,010 occurrences, two signatures, two entities")
    (d/transact conn tx)
    (let [r (err/record @conn s)]
      (is (= [1000 2 :eval 'app/f :a2 "t/a"]
             [(:seon.error/count r) (:seon.error/agents r) (:seon.error/class r) (:seon.error/operation r)
              (:seon.error/last-agent r) (:seon.error/last-task r)]))
      (is (= :seon.instrument/contract-error (get-in r [:seon.error/example :seon.error/type])) "the example is the error map, as data")
      (is (= [1000 1999] [(:seon.error/first-ms r) (:seon.error/last-ms r)])))
    (testing "an unchanged window writes nothing"
      (is (= [] (second (err/flush-tx f @conn)))))
    (testing "a written signature's later occurrences count in the fold, not on main; a new observer adds to the stored count"
      (let [[f tx] (err/flush-tx (reduce err/fold f (for [i (range 5)] (occ (invalid 'app/f i) :a1 3000))) @conn)]
        (is (= [] tx))
        (is (= 1005 (:count (get f (err/cause (invalid 'app/f 0))))) "the fold holds the true count")
        (is (= 1000 (:seon.error/count (err/record @conn s))) "main still holds the written one")
        (testing "the next write that has a signature to write carries the pending count"
          (let [[_ tx] (err/flush-tx (err/fold f (occ (invalid 'app/h 0) :a1 3500)) @conn)]
            (is (= #{1005 1} (set (map :seon.error/count tx))))))
        (let [[_ tx] (err/flush-tx (reduce err/fold {} (for [i (range 7)] (occ (invalid 'app/f i) :a9 4000))) @conn)]
          (d/transact conn tx)
          (is (= 1007 (:seon.error/count (err/record @conn s)))))))))))

;; ---- a repeated defect is not a write (task seon.error/a-repeated-defect-writes-nothing) ------

(deftest ^{:seon.test/estimate {:ticks 20000000}} a-written-signature-is-not-written-again
  ;; the window's write is one entity per signature that CHANGED (a-storm-is-one-write-per-window);
  ;; a signature already on main whose only change is one more occurrence is the same fact,
  ;; counted in the observer's fold: transacting its count is what made 41,709 commits in 70 s
  (ts/test-branch! (fn [_ conn] (let [f (reduce err/fold {} (for [i (range 3)] (occ (invalid 'app/f i) :a1 (+ 1000 i))))
        [f tx] (err/flush-tx f @conn)]
    (is (= 1 (count tx)) "the first occurrence writes the signature")
    (d/transact conn {:tx-data tx})
    (let [[_ tx2] (err/flush-tx (reduce err/fold f (for [i (range 1000)] (occ (invalid 'app/f i) :a1 (+ 2000 i)))) @conn)]
      (is (= [] tx2) "1,000 more occurrences of a written signature change no fact")
      (let [f (reduce err/fold f (for [i (range 1000)] (occ (invalid 'app/f i) :a1 (+ 2000 i))))]
        (is (= [3 1003] [(get-in (problems/report @conn) [:classes :eval :rows 0 :count])
                         (get-in (problems/report @conn f) [:classes :eval :rows 0 :count])])
            "the report reads the true count from the fold")))))))

;; ---- eval errors: the error map as data, on the branch, and counted across agents ------

(deftest ^{:seon.test/estimate {:ticks 2000000}} an-eval-error-is-recorded-as-data
  (testing "a foreign exception is the :seon.agent/eval-error variant"
    (let [e (agent/error-map (ArithmeticException. "Divide by zero"))]
      (is (contract/valid? :seon.agent/eval-error e))
      (is (= 'seon.repl/eval-code (:seon.error/operation e)))
      (is (re-find #"ArithmeticException" (:class e)))))
  (testing "a contract's error is its own error map: operation and problems kept"
    (let [r (invalid 'app/f "x")
          e (agent/error-map (ex-info (:seon.error/message r) r))]
      (is (= r e))))
  (testing "the response carries the map as :ex, and the stored eval record holds it as data"
    (let [em (agent/error-map (ArithmeticException. "Divide by zero"))
          r (repl/response [{"status" ["eval-error"] :tag :err :err em :id 0 :form "(/ 1 0)"}])
          rec (some :seon.eval/response (repl/record-tx {:seon.agent/key "b/a1"} "e1" (seon.task/sender "a1")
                                                          {:seon.eval/code "(/ 1 0)" :seon.eval/response r}))]
      (is (= em (:ex r)))
      (is (= em (:ex rec)) "grouped by type and producer without parsing text")))
  (testing "the agent folds its errors until its commit; root folds them as :eval occurrences"
    (let [em (agent/error-map (ArithmeticException. "Divide by zero"))
          m (-> nil (err/note em :eval "(/ 1 0)" 10) (err/note em :eval "(/ 2 0)" 12))
          [s] (vals m)
          o (err/sent->occurrence s :a4 "t/x")]
      (is (= [2 10 12 "(/ 1 0)"] [(:n s) (:first-ms s) (:last-ms s) (:form s)]))
      (is (= {:count 2 :agents #{:a4} :class :eval} (select-keys (err/fold-entry nil o) [:count :agents :class]))))))

;; ---- red proofs: the failing assertions and cost verdicts, persisted ---------------------

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-red-assertion-is-persisted
  (let [verdict {:ok? false :over [{:dim :calls :n 10 :ratio 2.0}] :steeper [] :under [] :unmeasured []}
        run {:pass 1 :fail 10 :error 0
             :reports (into [{:type :pass :var "#'app.t/ok"}
                             (agent/test-report {:type :fail :message (cost/explain {:of 'app/f :input 'app/in :n "n" :work "w" :growth :linear :sizes [1 10 100]} [] verdict)
                                                 :expected '(< cost estimate) :actual {:rows [] :verdict verdict}}
                                                "#'app.t/f-cost")]
                            (for [i (range 9)] (agent/test-report {:type :fail :message "nope" :expected (list '= 3 i) :actual (list 'not (list '= 3 i))} "#'app.t/red")))}
        fs (proofs/failures run)
        cs (proofs/costs run)]
    (is (= #{'app.t/red 'app.t/f-cost} (set (keys fs))))
    (is (= 9 (count (get fs 'app.t/red))) "every failing assertion of a test is kept")
    (is (contract/valid? :seon.task/failures fs))
    (is (= "(= 3 0)" (:expected (first (get fs 'app.t/red)))) "printed by the one printer")
    (is (= {'app.t/f-cost {:rows [] :verdict verdict}} cs) "a cost proof's rows and verdict, as data")))

;; ---- root's observations: deaths, stalls, platform errors, effect errors ----------------

(defn- api [& [view]]
  (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 64)
                    :view (merge {:agents {}} view) :graph (promise)
                    :system (doto (promise) (deliver {:procs {} :conns [] :parts {[:proc :api] [{:proc 'seon.system/api-proc} nil]}}))}))

(defn- step [st in m] (#'root/api-proc st in m))

(defn- writes [outs] (vec (keep #(when (= :tx (:op %)) (:tx-data %)) (:to-writer outs))))

(deftest ^{:seon.test/estimate {:ticks 10000000}} a-platform-error-is-written-once
  (let [msg {:op :error :pid :api :message "class java.lang.String cannot be cast to java.lang.Number (17)" :data nil}
        [st o1] (step (api) :back msg)
        [w] (writes o1)
        rid (get-in (first (:to-writer o1)) [:dest])
        [st outs] (reduce (fn [[st acc] _] (let [[st o] (step st :back msg)] [st (into acc (writes o))])) [st []] (range 500))
        _ (is (= [] outs) "one write in flight at a time: the storm only folds")
        [_ o3] (step st :from-writer {:dest rid :r {:ok true :t 9}})]
    (is (= 1 (:seon.error/count (first w))))
    (is (= :platform (:seon.error/class (first w))))
    (is (= 'seon.system/api-proc (:seon.error/operation (first w))) "the failed proc is the producer")
    (is (= [] (writes o3)) "the answered write is followed by nothing: 500 repeats of a written signature are counted in the fold")))

(deftest ^{:seon.test/estimate {:ticks 1000000}} an-agent-death-is-recorded-with-its-cause
  (let [dir (str (System/getProperty "java.io.tmpdir") "/d2-death-" (random-uuid))
        _ (.mkdirs (java.io.File. (str dir "-agents")))
        _ (spit (str dir "-agents/a1.err") (str (apply str (repeat 3000 "x")) "\nChez: out of memory\n"))
        p (promise)
        st (-> (api {:store {:path dir :agents-dir (str dir "-agents")} :agents {:a1 {:status :busy :pid 77 :task "t/x" :running {:form "(work!)" :since-ms 5}}}})
               (assoc-in [:agents :a1] {:branch :b1}) (assoc-in [:pending 3] {:p p :kind :eval :agent :a1}))
        ;; the api's end of an agent (its graph's update! is the api's, not this test's)
        end (fn [st m] (let [o (volatile! {}) st (#'root/end-agent st o m)] [(dissoc (#'root/flush-problems st o) :seon.system/update) @o]))
        [st o1] (end st {:op :ended :agent :a1 :rid nil :pid 77 :inflight [3]})
        ;; the caller is answered when the fork server reaps the child (its exit is the death's)
        _ (step st :from-fs {:ev :exit :pid 77 :status {:signal 9}})
        died @p]
    (testing "the caller in flight is answered with the death as the one error map, once its exit is known"
      (is (= :seon.agent/died (:seon.error/type died)))
      (is (contract/valid? :seon.agent/died died))
      (is (re-find #"out of memory" (:stderr died)) "the last lines of its stderr")
      (is (<= (count (:stderr died)) 2048) "bounded")
      (is (= {:kind :eval :form "(work!)" :since-ms 5} (:last-op died))))
    (testing "its :dead event carries the death, so a wait on it names it (a test's with-agent before the hello said {:end :dead})"
      (let [ev (first (filter #(= :dead (:type %)) (:events o1)))]
        (is (= :seon.agent/died (get-in ev [:error :seon.error/type])) (pr-str ev))
        (is (re-find #"out of memory" (str (get-in ev [:error :stderr]))) "its stderr's tail with it")))
    (testing "the exit status, reaped later by the fork server, completes the recorded death"
      (is (= [] (writes o1)) "not yet: the exit status is not known")
      (let [[_ o2] (step st :from-fs {:ev :exit :pid 77 :status {:signal 9}})
            [[w]] (writes o2)]
        (is (= [:agent :seon.agent/died :a1 "t/x"] [(:seon.error/class w) (:seon.error/type w) (:seon.error/last-agent w) (:seon.error/last-task w)]))
        (is (= {:signal 9} (:exit (:seon.error/example w))))))
    (testing "a stop root asked for is not a death"
      (let [st (-> (api {:agents {:a2 {:status :busy :pid 78}}}) (assoc-in [:agents :a2] {:branch :b2}))
            [st _] (end st {:op :ended :agent :a2 :rid 9 :pid 78 :inflight []})
            [_ o] (step st :from-fs {:ev :exit :pid 78 :status {:exit 0}})]
        (is (= [] (writes o)))))))

(deftest ^{:seon.test/estimate {:ticks 1000000}} root-records-what-went-wrong-in-an-agent
  ;; the one producer of what is wrong (root's problems tile, seon.render.ui/problems-tile, reads only what is recorded):
  ;; root records a red test run of an agent with no task, and a refused spawn
  (let [view {:agents {:a1 {:status :idle} :a2 {:status :idle :task "t/x"}}}
        red {:pass 1 :fail 1 :error 0 :reports [{:type :pass :var "#'app.t/ok"} {:type :fail :var "#'app.t/red"}]}
        written (fn [[_ o]] (mapv (juxt :seon.error/class :seon.error/type :seon.error/message :seon.error/last-agent) (first (writes o))))]
    (testing "a red run of an agent with no task"
      (is (= [[:agent :seon.agent/failed "tests red: #'app.t/red" :a1]]
             (written (step (api view) :back {:op :status :agent :a1 :set {:tests red}})))))
    (testing "a task's agent: it records its reds at its end, not root"
      (is (= [] (written (step (api view) :back {:op :status :agent :a2 :set {:tests red}})))))
    (testing "a refused spawn, of any agent"
      (is (= [[:agent :seon.agent/failed "fork server went down before the fork" :a2]]
             (written (step (api view) :back {:op :status :agent :a2
                                              :set {:error (contract/error :seon.agent/failed 'seon.system/spawn! "fork server went down before the fork" {:agent :a2})}})))))))

(deftest ^{:seon.test/estimate {:ticks 1000000}} a-stall-is-observed-by-the-agent-proc
  (let [end {:in (a/chan 4) :out (a/chan 16)}
        st (#'ap/agent-proc {:id :a1 :end end :branch :b1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4) :fx-kinds #{:fx/model}})
        [st _] (#'ap/agent-proc st :in {:op :req :rid 1 :kind :eval :msg {:op :eval :forms "(Thread/sleep 99999)"}})
        st (assoc st :last-frame-ms 1000)]
    (testing "an eval in flight, no frame for longer than the stall bound, no effect outstanding: stalled, once"
      (let [[st outs] (#'ap/agent-proc st :in {:op :stall? :now-ms 5000 :stall-ms 2000})
            s (first (filter #(= :stalled (:op %)) (:to-api outs)))]
        (is (= :seon.agent/stalled (get-in s [:error :seon.error/type])))
        (is (= 4000 (get-in s [:error :quiet-ms])))
        (is (contract/valid? :seon.agent/stalled (:error s)))
        (is (empty? (:to-api (second (#'ap/agent-proc st :in {:op :stall? :now-ms 6000 :stall-ms 2000})))) "reported once per quiet spell")))
    (testing "an effect outstanding is waiting on its owner, not stalled"
      (let [[st _] (#'ap/agent-proc st :from {:op :fx :k 1 :kind :model})
            st (assoc st :last-frame-ms 1000)]
        (is (empty? (:to-api (second (#'ap/agent-proc st :in {:op :stall? :now-ms 9000 :stall-ms 2000})))))))
    (testing "a test of its run that started is what a stall names; its first frame after the stall ends it"
      (let [[st _] (#'ap/agent-proc st :from {:op :testing :test "#'app.t/hangs"})
            st (assoc st :last-frame-ms 1000)
            [st outs] (#'ap/agent-proc st :in {:op :stall? :now-ms 5000 :stall-ms 2000})
            [_ outs2] (#'ap/agent-proc st :from {:op :testing :test "#'app.t/next"})]
        (is (= {:kind :tests :form "#'app.t/hangs"} (get-in (first (:to-api outs)) [:error :last-op])) "the stall names the test")
        (is (some #(= {:op :spoke :agent :a1} %) (:to-api outs2)) "its next frame: it spoke")))))

(deftest ^{:seon.test/estimate {:ticks 1000000}} a-stall-is-a-fact-on-the-agent-until-it-speaks-or-ends
  ;; root, 2026-10-26 (the owner: "the hangs are super bad especially if you don't notice an agent
  ;; has been stuck"): a stall root observes is a fact on the agent, with what it waits on, so
  ;; my.root/problems, root's page and every ctx holding the agent show it, until the agent's next
  ;; frame or its end retracts it
  (let [view {:agents {:a1 {:status :busy :pid 77 :task "t/x"}}}
        waits {:kind :tests :form "#'app.t/hangs"}
        stalled {:op :stalled :agent :a1
                 :error (contract/error :seon.agent/stalled 'seon.agent-proc/agent-in "agent a1 sent nothing for 4000 ms with a request in flight"
                                        {:agent :a1 :quiet-ms 4000 :last-op waits})}
        facts (fn [o] (into [] cat (writes o)))
        retracts? (fn [o] (some #(and (vector? %) (= :seon.process/stall (last %))) (facts o)))
        [st o1] (step (api view) :back stalled)]
    (is (some #(= {:quiet-ms 4000 :last-op waits} (:seon.process/stall %)) (facts o1)) "recorded on the agent, with what it waits on")
    (is (retracts? (second (step st :back {:op :spoke :agent :a1}))) "retracted when it speaks")
    (is (retracts? (second (step st :back {:op :status :agent :a1 :set {:status :dead}}))) "and when it ends")))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-stall-in-a-live-task-makes-one-task-for-root
  ;; owner, 2026-10-26: no push; a stall "becomes a task for root to deal with, from a template":
  ;; the first write of a stall in a live task writes one task (seon.template/stall), its cause
  ;; pre-filled with the stall, its proofs the boundary conditions; a later stall of the same task
  ;; writes none (one per agent and task), and an agent working no task gets none
  (let [db (world)
        view {:agents {:a1 {:status :busy :pid 77 :task "demo/t"} :a2 {:status :busy :pid 78}}}
        stalled (fn [ag] {:op :stalled :agent ag
                          :error (contract/error :seon.agent/stalled 'seon.agent-proc/agent-in (str "agent " (name ag) " sent nothing for 4000 ms with a request in flight")
                                                 {:agent ag :quiet-ms 4000 :last-op {:kind :tests :form "#'app.t/hangs"}})})
        tasks (fn [o] (filter :seon.task/id (into [] cat (writes o))))
        [_ o1] (step (assoc (api view) :conn (atom db)) :back (stalled :a1))
        [t] (tasks o1)]
    (is (= ["root/stall-a1-demo-t"] (mapv :seon.task/id (tasks o1))) "one task, named for the agent and its task")
    (is (= {:agent :a1 :task "demo/t" :quiet-ms 4000 :pid 77 :last-op {:kind :tests :form "#'app.t/hangs"}} (dissoc (get-in t [:seon.task/cause :stall]) :api))
        "its cause pre-filled with the stall")
    (is (= {:open [] :prove nil} (get-in t [:seon.task/cause :stall :api]))
        "and what the api held then: no ask open, no report from the test service yet")
    (is (= [:held "outside" seon.template/stall-proofs] [(:seon.task/execution t) (:seon.task/model t) (set (:seon.task/proofs t))])
        "held, for an outside worker, its proofs the boundary conditions")
    (is (empty? (tasks (second (step (assoc (api view) :conn (atom (d/db-with db (vec (tasks o1))))) :back (stalled :a1)))))
        "a later stall of the same task writes none")
    (is (empty? (tasks (second (step (assoc (api view) :conn (atom db)) :back (stalled :a2))))) "an agent working no task: none")))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-stall-tasks-evidence-is-captured-while-it-is-stuck
  ;; the evening audit (a6's hang left no evidence: the root restarted and the process went with
  ;; it): the stall task's first write asks for the stuck process's evidence (the :evidence effect,
  ;; seon.fx/stall-evidence), and its answer's files are written into the task's cause on main
  (let [db (world)
        view {:agents {:a1 {:status :busy :pid 77 :task "demo/t"}}}
        st (assoc (api view) :conn (atom db) :system {:procs {:fx/evidence {}}})
        stalled {:op :stalled :agent :a1
                 :error (contract/error :seon.agent/stalled 'seon.agent-proc/agent-in "agent a1 sent nothing for 4000 ms with a request in flight"
                                        {:agent :a1 :quiet-ms 4000})}
        [st o1] (step st :back stalled)
        [ask] (get o1 [:fx/evidence :ask])
        db (d/db-with db (vec (filter :seon.task/id (into [] cat (writes o1)))))
        files {:sample "/x/sample.txt" :lsof "/x/lsof.txt"}
        [_ o2] (step (assoc st :conn (atom db)) :back {:dest (:dest ask) :r {:value {:dir "/x" :files files}}})
        [[_ _ attr cause]] (filter #(and (vector? %) (= :seon.task/cause (nth % 2 nil))) (into [] cat (writes o2)))]
    (is (= 77 (get-in ask [:req :pid])) "asked for the stuck process")
    (is (str/ends-with? (get-in ask [:req :dir]) "/stalls/stall-a1-demo-t") "its files under the runs directory's stalls/, by the task")
    (is (= :seon.task/cause attr))
    (is (= files (:evidence cause)) "the evidence's files on the task's cause")
    (is (= :a1 (get-in cause [:stall :agent])) "beside the stall it was captured for")))

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-stalled-agent-is-an-open-problem-while-the-fact-holds
  (let [db (d/db-with (world) [{:seon.agent/key "a1"}
                                    {:seon.process/id "a1/77" :seon.process/agent [:seon.agent/key "a1"] :seon.process/pid 77 :seon.process/forked-ms 10.0
                                     :seon.process/stall {:quiet-ms 4000 :last-op {:kind :tests :form "#'app.t/hangs"}}}
                                    {:seon.agent/key "a2"}])
        rows (get-in (problems/health db) [:classes :stalled :rows])]
    (is (= [[:a1 77 "#'app.t/hangs"]] (mapv (juxt :agent (comp :pid :evidence) (comp :form :waits-on :evidence)) rows)) "one row, the stalled agent's")
    (is (re-find #"a1 sent nothing for 4000 ms.*tests #'app.t/hangs" (:message (first rows))) "saying what it waits on")
    (is (empty? (get-in (problems/health (d/db-with db [[:db.fn/retractAttribute [:seon.process/id "a1/77"] :seon.process/stall]])) [:classes :stalled :rows]))
        "closed once the fact is retracted")
    (is (empty? (get-in (problems/health (d/db-with db (seon.task/earlier-agents-tx db))) [:classes :stalled :rows]))
        "and by a new boot: an earlier boot's stall ended with its process")
    ;; a stall left on an earlier process of the agent is no row: only its current process's counts
    (is (empty? (get-in (problems/health (d/db-with db [{:seon.process/id "a1/78" :seon.process/agent [:seon.agent/key "a1"] :seon.process/pid 78 :seon.process/forked-ms 20.0}]))
                        [:classes :stalled :rows]))
        "a respawned agent's old process's stall is no row")))

(defn- gone-ended? [r] (= :seon.system/ended (:seon.error/type r)))

(deftest ^{:seon.test/estimate {:ticks 1000000}} a-late-live-status-leaves-a-gone-agent-gone
  ;; task seon.system/a-gone-agent-stays-gone-and-every-request-is-answered, path A
  (testing "A: a late live status leaves a gone agent gone, out of the stall check's set"
    (let [[st _] (step (api {:agents {:a1 {:status :dead :pid 77 :ended-ms 5}}}) :back {:op :status :agent :a1 :set {:status :busy :running {:form "(work!)" :since-ms 6}}})]
      (is (= [:dead nil] ((juxt :status :running) (get-in st [:view :agents :a1]))))
      (is (not (contains? (:seon.system/busy st) :a1))))))

(deftest ^{:seon.test/estimate {:ticks 1000000}} an-ending-agents-every-request-is-answered
  ;; task seon.system/a-gone-agent-stays-gone-and-every-request-is-answered, path A
  (testing "A: an ending agent's every request the api holds is answered, one that never reached its proc too; a prove is the test service's"
    (let [p (promise) q (promise)
          st (-> (api {:agents {:a1 {:status :killed :pid 77}}}) (assoc-in [:agents :a1] {:branch :b1})
                 (assoc-in [:pending 5] {:p p :kind :eval :agent :a1}) (assoc-in [:pending 6] {:p q :kind :prove :agent :a1}))
          o (volatile! {})
          st' (#'root/end-agent st o {:op :ended :agent :a1 :rid nil :pid 77 :inflight []})]
      (is (gone-ended? (deref p 0 nil)) "the eval sent as it ended: answered")
      (is (= ::unanswered (deref q 0 ::unanswered)) "the prove: left to the service that answers it")
      (is (contains? (:pending st') 6)))))

(deftest ^{:seon.test/estimate {:ticks 1000000}} a-stopped-agents-exit-is-recorded-on-it
  ;; task seon.system/a-gone-agent-stays-gone-and-every-request-is-answered, path B
  (testing "B: a stopped agent's exit, which no death waits on, is still recorded on it"
    (let [[st _] (step (api {:agents {:a0 {:status :dead :pid 77 :ended-ms 1 :exit {:exit 0}} :a1 {:status :killed :pid 77 :ended-ms 9}}})
                       :from-fs {:ev :exit :pid 77 :status {:signal 9}})]
      (is (= [{:exit 0} {:signal 9}] (mapv #(get-in st [:view :agents % :exit]) [:a0 :a1]))))))

(deftest ^{:seon.test/estimate {:ticks 1000000}} an-exit-goes-to-the-death-waiting-on-its-pid
  ;; task seon.system/a-gone-agent-stays-gone-and-every-request-is-answered, path B
  (testing "B: an exit goes to the death waiting on its pid, never to an ended agent's entry with the same pid"
    (let [died (contract/error :seon.agent/died 'seon.system/end-agent "agent a1 died" {:agent :a1 :pid 77})
          st (-> (api {:agents {:a0 {:status :dead :pid 77 :ended-ms 1} :a1 {:status :dead :pid 77 :ended-ms 9}}})
                 (assoc :seon.system/dying {77 [:a1 died []]}))
          [st _] (step st :from-fs {:ev :exit :pid 77 :status {:signal 9}})]
      (is (= [nil {:signal 9}] (mapv #(get-in st [:view :agents % :exit]) [:a0 :a1])))
      (is (empty? (:seon.system/dying st)) "the death recorded"))))

(deftest ^{:seon.test/estimate {:ticks 1000000}} the-hello-answers-the-spawn-whatever-its-status
  ;; task seon.system/a-gone-agent-stays-gone-and-every-request-is-answered, path D
  (testing "D: the hello answers the spawn though an eval sent before it made the agent busy"
    (let [p (promise)
          st (-> (api {:agents {:a1 {:status :busy :pid 77}}}) (assoc-in [:seon.system/spawning :a1] p))
          _ (step st :back {:op :status :agent :a1 :set {:ready true}})]
      (is (= {:id :a1} (deref p 0 nil))))))

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-new-boot-ends-an-earlier-boots-live-agents
  ;; task seon.system/a-gone-agent-stays-gone-and-every-request-is-answered, path C
  (testing "C: a new boot writes :dead for an earlier boot's agent still shown live"
    (let [db (d/db-with (world) [{:db/id "r" :seon.system/id :root} {:seon.agent/key "b0/a1" :seon.agent/root "r"
                                                            :seon.agent/status :busy}
                                                            {:seon.process/id "b0/a1/5" :seon.process/agent [:seon.agent/key "b0/a1"] :seon.process/running "(work!)"}])
          db (d/db-with db (seon.task/earlier-agents-tx db))]
      (is (= [:dead nil] [(:seon.agent/status (d/entity db [:seon.agent/key "b0/a1"])) (:seon.process/running (d/entity db [:seon.process/id "b0/a1/5"]))]))))
  ;; docs/users.md#roots-agent: root's own session (key "root") outlives a boot and keeps its link to root, as the user does, so root's
  ;; pull through it (:seon.agent/root, then :seon.agent/_root) reaches its agents after a restart, an earlier boot's among them (root's page holds every boot's).
  ;; Red before: every agent but a user lost the link, root's session too, and an earlier boot's sessions were reached only by a scan
  (testing "a new boot keeps root's session and an earlier boot's agent linked to root"
    (let [db (d/db-with (world) [{:db/id "r" :seon.system/id :root}
                                 {:seon.agent/key "root" :seon.agent/root "r" :seon.agent/status :busy}
                                 {:seon.agent/key "b0/a2" :seon.agent/root "r" :seon.agent/status :idle}])
          db (d/db-with db (seon.task/earlier-agents-tx db))
          root-e (:db/id (d/entity db [:seon.system/id :root]))]
      (is (= root-e (:db/id (:seon.agent/root (d/entity db [:seon.agent/key "root"])))) "root's session keeps its link")
      (is (= :dead (:seon.agent/status (d/entity db [:seon.agent/key "root"]))) "though its process ended with the boot")
      (is (= root-e (:db/id (:seon.agent/root (d/entity db [:seon.agent/key "b0/a2"])))) "an earlier boot's agent keeps its link"))))

(deftest ^{:seon.test/estimate {:ticks 1000000}} an-effect-error-is-counted-by-root
  (testing "the owner of an effect kind tells the api about each error outcome"
    (let [st (#'fx/owner-proc {:kind :model :workers 1 :work (a/chan 4) :back (a/chan 4)})
          outcome (contract/error :seon.model/rate-limited 'seon.model/complete "HTTP 429: slow down" {:status 429})
          [_ outs] (#'fx/owner-proc (assoc st :busy 1) :done {:dest {:agent :a5 :op :fx-reply :k 1} :outcome outcome})
          pr (first (filter #(= :problem (:op %)) (:out outs)))]
      (is (= [:effect :a5 :seon.model/rate-limited] [(:class pr) (:agent pr) (:seon.error/type (:error pr))]))))
  (testing "HTTP 429 is produced as :seon.model/rate-limited (transient: the :model owner keeps the request), its body whole"
    (let [e (#'model/provider-error "HTTP 429" 429 {:body "too many" :retry-after-ms 2000})]
      (is (= [:seon.model/rate-limited 429 "too many" 2000] ((juxt :seon.error/type :status :body :retry-after-ms) e))))
    (is (= :seon.model/provider-error (:seon.error/type (#'model/provider-error "HTTP 500" 500 {:body "oops"}))))))

;; root code records its own failed effect with its fact, once (root, 2026-10-26); before, root's api
;; counted it in a transaction of its own, which raced the asker's write of the fact, so a row naming
;; the fact could not close at the next good outcome
(deftest ^{:seon.test/estimate {:ticks 5000000}} an-askers-failed-effect-is-one-row-closed-by-its-next-good-outcome
  (let [co (doto (java.io.File. (str (System/getProperty "java.io.tmpdir") "/seon-unreadable-" (random-uuid)) "src") (.mkdirs))
        _ (doto (java.io.File. co "a.clj") (spit "(ns a)") (.setReadable false))
        ;; the source's read failed, as its owner answers it: stamped with when (seon.fx/worker-proc)
        failed (assoc (fx/read-source {:req {:root (.getParent co) :dirs ["src"]}}) :seon.fx/at-ms 1000)
        rows #(get-in (problems/report %) [:classes :effect :rows])
        open? #(not (false? (:open? (first (rows %)))))
        import (fn [db r fact] (d/db-with db (err/asked-tx db r :seon.system/source [{:seon.system/id :root :seon.system/source fact}])))
        bad {:root "/c" :files 0 :forms 0 :ms 1.0 :error (:seon.error/message failed)}
        good {:root "/c" :files 1 :forms 1 :ms 2.0 :digest "d"}]
    (is (= :seon.fx/io-error (:seon.error/type failed)) "an unreadable file fails the read")
    (testing "a failed import, then a good one"
      (let [db (import (world) failed bad)]
        (is (= 1 (count (rows db))) "one row for the failure")
        (is (open? db) "its fact is written in the same transaction: open")
        (let [again (import db failed bad)]
          (is (open? again) "the same failure again: open")
          (is (= [2] (map :count (rows again))) "one row, counted twice"))
        (let [fixed (import db {:value {}} good)]
          (is (= 1 (count (rows fixed))) "still one row")
          (is (false? (:open? (first (rows fixed)))) "the good import writes another fact: closed"))))
    (testing "the reconciler's commit: a failed :git, then the next merge's sha"
      (let [git (assoc (contract/error :seon.fx/io-error 'seon.fx/git-commit "git commit: index.lock exists" {}) :seon.fx/at-ms 2000)
            db0 (world)
            db (d/db-with db0 (err/asked-tx db0 git :seon.task/commit [{:seon.task/id "t/r1" :seon.task/error (contract/error :seon.git/commit-failed 'seon.reconcile/commit-tx (:seon.error/message git) {:seon.git/files ["src/a.clj"]})}]))
            fixed (d/db-with db (err/asked-tx db {:value {:sha "abc"}} :seon.task/commit [{:seon.task/id "t/r2" :seon.task/commit "abc"}]))]
        (is (= 1 (count (rows db))))
        (is (open? db) "a failed commit writes no sha: open")
        (is (false? (:open? (first (rows fixed)))) "the next merge's commit writes one: closed")))
    (testing "an error the asker made itself (no owner's stamp) writes its fact alone"
      (is (= [{:a 1}] (err/asked-tx (world) (contract/error :seon.fx/io-error 'seon.reconcile/gated "no gate checkout" {}) :seon.task/deferred-results [{:a 1}]))))
    (.setReadable (java.io.File. co "a.clj") true)
    (run! #(.delete ^java.io.File %) (reverse (file-seq (.getParentFile co))))))

;; ---- the read -----------------------------------------------------------------------------

(defn- ended
  "Task tid (a work task) as its work ended: m, the :seon.task/* of how (its outcome, failures, error), by the agent
  b/a1, which ended at ms 2 (an agent holds its own end; the task points to it)."
  {:malli/schema [:=> [:cat :string :map] :map]}
  [tid m]
  (merge {:seon.task/id tid :seon.task/ns 't :seon.task/name (subs tid 2) :seon.task/plan "p" :seon.task/execution :running
          :seon.task/branch :b :seon.task/kind :work
          :seon.task/agent {:seon.agent/key "b/a1" :seon.agent/ended-ms 2}}
         m))

(defn- seeded
  "A main db with n recorded errors, n red tasks and n dead tasks (each ended by its agent), plus k clean
  tasks done (the program the read must not cost), on the world (my.test)."
  [n k]
  (let [db (world)
        f (reduce err/fold {} (for [i (range n)] (err/occurrence {:seon.error/type :seon.agent/eval-error :seon.error/operation 'seon.repl/eval-code
                                                                  :seon.error/message (str "Unable to resolve symbol: s" (char (+ 97 (mod i 26))) i) :class "x"}
                                                                 :eval {:agent :a1 :task "t/x0"} 100)))
        [_ tx] (err/flush-tx f db)]
    (d/db-with (d/db-with db tx)
               (vec (concat (for [i (range k)] (ended (str "t/c" i) {:seon.task/outcome :done}))
                            (for [i (range n)] (ended (str "t/r" i)
                                                      {:seon.task/proofs #{'app.t/red} :seon.task/outcome :cap
                                                       :seon.task/failures {'app.t/red [{:type :fail :message "m" :expected "1" :actual "2"}]}}))
                            (for [i (range n)] (ended (str "t/d" i)
                                                      {:seon.task/outcome :dead :seon.task/reason "its agent died"
                                                       :seon.task/error (contract/error :seon.agent/died 'seon.system/end-agent "agent a1 died" {:agent :a1})})))))))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-cancelled-tasks-problems-close
  (let [db (seeded 2 1)
        red (fn [db] (get-in (problems/report db) [:classes :red :rows]))
        dead (fn [db] (get-in (problems/report db) [:classes :dead :rows]))]
    (is (every? :open? (red db)) "a red task still to be worked is open")
    (is (every? :open? (dead db)))
    (let [cancelled (d/db-with db [{:seon.task/id "t/r0" :seon.task/execution :cancelled}
                                   {:seon.task/id "t/d0" :seon.task/execution :cancelled}])
          open (fn [rows task] (:open? (first (filter #(= task (:task %)) rows))))]
      (is (false? (open (red cancelled) "t/r0")) "the cancelled task's red row closes")
      (is (false? (open (dead cancelled) "t/d0")) "and its dead row")
      (is (true? (open (red cancelled) "t/r1")) "another task's stays open"))))

(defn- measured-health [db]
  (let [m (profile/measure! #(problems/health db))]
    [(:value m) (dissoc m :value)]))

(defn- calls-of
  "The contracted calls of thunk f."
  [f]
  (:calls (profile/measure! f)))

(deftest ^{:seon.test/estimate {:ticks 10000000}} health-reads-each-part-once
  (let [db0 (seeded 3 2)
        db (d/db-with db0 (seon.doc/load-tx db0 "demo" {"demo/p.md" "# P\n\nIt says what it is about.\n"}))
        [h m] (measured-health db)
        rows (get-in h [:classes :problem-unlinked :rows])
        ans (problems/answers db)
        classes {:stalled #(problems/stalled-agents db ans) :stale-pin #(problems/stale-pins db ans)
                 :stale-binary #(problems/stale-binary db ans) :orphan-page #(problems/orphan-pages db ans)
                 :leadless-section #(problems/leadless-sections db ans) :uncited-task #(problems/uncited-tasks db ans)
                 :unnamed-site #(problems/unnamed-sites db ans) :stale-section #(problems/stale-sections db ans)
                 :stale-plan #(problems/stale-plans db ans) :waived-proof #(problems/waived-proofs db ans)
                 :unsynced #(problems/unsynced-tasks db ans (mapv :e (d/datoms db :aevt :seon.task/id)))
                 ;; the remainder: answers, report-with, health-with (and problem-unlinked inside it, over the derived rows as health hands them), and the folds
                 :fixed (let [derived (#'problems/other-derived db ans (mapv :e (d/datoms db :aevt :seon.task/id)))]
                          #(let [a (problems/answers db)]
                             (#'problems/health-with db a (:classes (#'problems/report-with db a {} nil)) derived)))}
        measured (update-vals classes calls-of)
        ;; 74 -> 86 -> 88 -> 89 on 2026-11-10 (uncited-tasks every ready task, stale-binary, waived-proof), one total; per class since. Each bound is the contracted calls measured on this world.
        bounds {:stalled 1             ; the processes holding a stall fact, one range
                :stale-pin 2           ; the pins of the branch scheme
                :stale-binary 2        ; root's source fact: one seon.db/eid and one pull
                :orphan-page 1         ; the sections' references, one scan
                :leadless-section 1    ; the stored sections' first blocks
                :uncited-task 15       ; the ready tasks' plans, cites and proofs (12 are the proofs read, seon.db/eid per proof), and task-reads (19 while it asked the program which cited vars were its own)
                :unnamed-site 9        ; the catch sites of the program's forms (live? and form-of per form, the namespaces once)
                :stale-section 2       ; the read datoms and the vars' forms, one scan each
                :stale-plan 3          ; task-reads, and a seon.db/eid per cited var of a ready task
                :waived-proof 1        ; the test forms' cost proofs passing by :overrun, one scan (Deep fixes' class)
                ;; its own and unsynced-candidates' contracts, and per task that may differ from its file at most 3: unsynced?,
                ;; derived? and its seon.db/eid of the refine section; a filed one's edit and a refine's cite are seeks, no call
                ;; (data, 2026-11-14: the store is the source, so every live task is judged)
                :unsynced (+ 2 (* 3 (count (#'problems/unsynced-candidates db (mapv :e (d/datoms db :aevt :seon.task/id))))))
                :fixed 52              ; (50 -> 52, data 11-14: this world's own store-made tasks give :unsynced rows, so problem-unlinked seeks its section, seon.db/eid, and writes its row, derived-row) answers, report-with (every recorded class), health-with (problem-unlinked over all of them, the derived rows as health hands them)
                :wrappers 2}]         ; health's and other-derived's own contracts
    (is (seq rows) "the db has unlinked classes to judge")
    (is (= rows (vec (distinct rows))) "no row twice")
    (is (= :problem-unlinked (:class (peek rows))) "the unlinked class's own missing section is the last row")
    (doseq [[c bound] (dissoc bounds :wrappers)]
      (is (<= (measured c) bound) (str c " reads " (measured c) " contracted calls, bound " bound)))
    ;; task-reads is in uncited-task's, stale-plan's and unsynced's rows (each measured alone makes it), and health makes it once (other-derived)
    (is (<= (:calls m) (- (apply + (vals bounds)) 2)) (str "one health read makes " (:calls m) " contracted calls: the classes' bounds sum to " (apply + (vals bounds)) ", less task-reads counted three times"))))

(deftest ^{:seon.test/estimate {:ticks 20000000}} problems-lists-every-class-with-what-a-declaration-needs
  (let [db (seeded 3 5)
        s (:signature (first (get-in (problems/report db) [:classes :eval :rows])))
        [_ conn] [nil nil]
        r (problems/report db)]
    (is (contract/valid? :seon.problems/report r))
    (is (= 3 (get-in r [:classes :eval :n])))
    (is (= 3 (count (get-in r [:classes :red :rows]))))
    (is (= 3 (count (get-in r [:classes :dead :rows]))))
    (let [red (first (get-in r [:classes :red :rows]))]
      (is (= ['app.t/red] (:proofs red)) "the failing test reproduces it")
      (is (= {:from (:task red) :red ['app.t/red]} (select-keys (:evidence red) [:from :red])) "evidence as data")
      (is (= 't (:owner-ns red)))
      (is (:open? red)))
    (let [e (first (get-in r [:classes :eval :rows]))]
      (is (= 'seon.repl/eval-code (:producer e)))
      (is (= :error (get-in e [:evidence :kind])))
      (is (= [] (:answered-by e)))
      (is (some? s)))
    (testing "a merge: its conflicts, unmerged keys and the writer's refused answer, as evidence"
      (let [behind (contract/error :seon.merge/behind 'seon.writer/merge-refused "main changed 2 key(s)" {:behind 2})
            db2 (:db-after (d/with db [(ended "t/m" {:seon.task/kind :merge :seon.task/agent {:seon.agent/key "b/a2" :seon.agent/ended-ms 9}
                                                     :seon.task/outcome :behind :seon.task/unmerged #{"[:var app/g]"}
                                                     :seon.task/conflicts [{:key [:var 'app/f]}]
                                                     :seon.task/error behind})]))
            [m] (get-in (problems/report db2) [:classes :merge :rows])]
        (is (= ["t/m" :behind 1 ["[:var app/g]"] 'seon.writer/merge-refused]
               [(:task m) (:status m) (:conflicts m) (:unmerged m) (:producer m)]))
        (is (= [{:key [:var 'app/f]}] (get-in m [:evidence :conflicts])))
        (is (= :seon.merge/behind (get-in m [:error :seon.error/type])))))
    (testing "a task whose evidence names a problem answers it: mining files no duplicate"
      (ts/test-branch! (fn [_ c] (let [_ (d/transact c (vec (d/datoms db :eavt)))
            db2 db
            sig (:signature (first (get-in r [:classes :eval :rows])))
            db3 (:db-after (d/with db2 [{:seon.task/id "fix/e" :seon.task/ns 'fix :seon.task/name "e" :seon.task/plan "p" :seon.task/execution :held
                                          :seon.task/branch :f :seon.task/kind :work :seon.task/evidence {:kind :error :signature sig}}]))
            r3 (problems/report db3)]
        (is (= ["fix/e"] (:answered-by (first (filter #(= sig (:signature %)) (get-in r3 [:classes :eval :rows]))))))))))))

(defn rows-input
  "The cost proof's input at n: a main db with n problems of each of three classes over a
  fixed clean program."
  [n] [(seeded n 4)])

(deftest ^{:seon.test/estimate {:ticks 20000000}} problems-reads-only-the-problems
  (cost/proof {:of 'seon.problems/report :input 'seon.problems-test/rows-input
               :n "recorded problems of each class (per n: one signature, one red task, one dead task)"
               :work (str "one index seek per class, then per row one pull: the signature's 13 attributes; a task's "
                          "outcome attributes and its agent's 2 (a nested pull), and one status pull to know whether it is done; about 500 "
                          "characters of EDN read (example, failures, error)")
               :growth :linear
               :ops {:db/pull 7 :db/pull-attr 38 :edn/read-char 500}
               :sizes [2 6 18]
               ;; its waiver (task seon.problems/report-beyond-its-pulls, never filed) closed 2026-11-10: with it deleted the proof
               ;; is green, 0.95x the ticks and 0.91x the bytes of its bound at 18
               }))

(defn clean-input
  "The flatness proof's input at k: 4 problems of each class over k clean tasks, each done."
  [k] [(seeded 4 k)])

(defn- diff [base head] (apply concat (d/datoms-diff base head :eavt [])))

(defn history-input
  "The flatness proof's input at k: the health of main with 4 problems of each class beside k finished
  tasks, each merged by an agent of its own as production writes it (judge-tx records its cost verdict and its
  waste too), then main one task's ending later: [health base head moved]."
  [k] (let [base (d/db-with (world)
                            (vec (for [i (range k)]
                                   (ended (str "t/h" i)
                                          {:seon.task/agent {:seon.agent/key (str "b/h" i) :seon.agent/ended-ms 2}
                                           :seon.task/outcome :merged
                                           :seon.task/costs {'app.t/cheap {:verdict {:ok? true :under []} :rows []}}
                                           :seon.task/waste {:turns 1}}))))
            head (d/db-with base [{:seon.agent/key "b/h0" :seon.agent/ended-ms 4}
                                  {:seon.task/id "t/h0" :seon.task/merged-t 7}])]
        [(problems/health base) base head (diff base head)]))

(defn own-input
  "The proof's input at k: a db with k finished tasks beside the agent of one: [db agent-entity]."
  [k] (let [[_ base] (history-input k)
            ;; one agent working a task, as production has beside the others' ended ones
            db (d/db-with base [{:seon.agent/key "b/own" :seon.agent/boot "b" :seon.agent/started-ms 1 :seon.agent/branch :b
                                 :seon.agent/max-turns 2 :seon.agent/task [:seon.task/id "t/h0"]}])]
        [db (pdb/eid db :seon.agent/key "b/own")]))

(deftest ^{:seon.test/estimate {:ticks 30000000}} an-agents-own-problems-cost-nothing-for-the-history
  (cost/proof {:of 'seon.problems/own :input 'seon.problems-test/own-input
               :n "finished tasks, each merged by an agent of its own, beside the agent asked for"
               :work "the agent's task's pull (its outcome, agent and attributes), and the error signatures' last-task datoms: none per finished task"
               :growth :constant
               :ops {:db/pull 0 :db/pull-attr 0 :edn/read-char 0}
               :fixed {:ticks 85000 :bytes 320000 :calls 4}
               :sizes [10 30 100]}))

(deftest ^{:seon.test/estimate {:ticks 30000000}} problems-move-at-the-cost-of-what-changed
  ;; main moves by one task's ending while its finished history grows: the health of the move reads the task it
  ;; touched, not the history (health-moved; report read every task's cost verdict and waste)
  (cost/proof {:of 'seon.problems/health-moved :input 'seon.problems-test/history-input
               :n "finished tasks, each merged with a cost verdict and waste"
               :work "no pull and no contracted call per finished task: the move's datoms (given, the render proc's) name the touched task and its agent's task, the derived classes are carried and read where the two dbs differ; the program's changes come from the moved datoms (seon.merge/delta-changes given them), and the per-task part is the cold index nodes the task scans touch"
               :growth :linear
               :per-n {:ticks 1700 :bytes 10500 :calls 0}
               ;; fixed calls, once a read whatever n, summed by what makes them: Point, 2026-11-10 (in Sean's absence, reported to him): 54 -> 56
               :fixed {:ticks 650000 :bytes 3000000 :calls (+ 54   ; the read before 2026-11-10 (with :waived-proof's one)
                                                              1    ; seon.problems/task-reads: the tasks' readiness and cites, once
                                                              1    ; seon.problems/stale-plans' own contract
                                                              1    ; and its seon.db/eid of the var the world's ready task cites
                                                              1    ; seon.problems/unsynced-tasks' own contract (Optimization, 2026-11-10: a seek a declared task, no call)
                                                              6    ; caused-split's content-class readers on base and head, for the move's candidates only
                                                                   ; (Data model, 2026-11-12: nothing switched off; measured 58 -> 64)
                                                              3    ; seon.problems/unschemed-keys: on base and head for the move's forms, and in the full read (data, 64 -> 67)
                                                              2    ; seon.problems/refused-values' contract on base and head (data, 67 -> 69)
                                                              4    ; the test law's two classes (unestimated-tests, undeclared-tests), one reader each on
                                                                   ; base and head (tests, 2026-11-12: 69 -> 73 with data's)
                                                              8    ; :unfocused-docstring (data, 73 -> 81 measured): its reader and its bound on base and head,
                                                                   ; and a seek and a row for each changed Seon var the input's tree holds
                                                              5)}  ; :unsynced judges the move's touched tasks, not only merged ones (data, 2026-11-14,
                                                                   ; 81 -> 86): unsynced-candidates' contract, and the touched task's unsynced?, derived?,
                                                                   ; and refine?'s eid and cites-of
               :sizes [10 30 100]}))

(deftest ^{:seon.test/estimate {:ticks 25000000}} problems-cost-nothing-for-a-clean-program
  (cost/proof {:of 'seon.problems/report :input 'seon.problems-test/clean-input
               :n "clean tasks (each done), beside 4 problems of each class"
               :work "none per clean task: the indexes the read seeks hold only problem rows; the 4 problem sets cost what they cost"
               :growth :constant
               :ops {:db/pull 28 :db/pull-attr 152 :edn/read-char 2000}
               :fixed {:bytes 750000}
               :sizes [10 30 100]}))

;; ---- the model call: thinking mode as a provider field, its cost and its empty reply as data ----

(def ^:private deepseek {:kind :openai-compat :base-url "https://api.example" :model "deepseek-flash" :api-key-env "NONE" :max-tokens 2048})
(def ^:private req {:messages [{:role "user" :content "hi"}] :task "t/a" :turn 0})

(deftest ^{:seon.test/estimate {:ticks 1000000}} the-request-sets-thinking-only-when-the-provider-does
  ;; tool_choice auto: the model may reply in text alone, which ends the turn (design, owner 2026-10-24)
  (is (= {:model "deepseek-flash" :messages (:messages req) :tools [model/eval-tool] :tool_choice "auto" :max_tokens 2048 :stream true :stream_options {:include_usage true}}
         (model/request-body deepseek req)) "thinking absent: the provider's default; max-tokens the provider's; the model chooses whether to call; streamed with its usage")
  (is (= {:type "disabled"} (:thinking (model/request-body (assoc deepseek :thinking :disabled) req))))
  (is (= ["high" 512] ((juxt :reasoning_effort :max_tokens) (model/request-body (assoc deepseek :reasoning-effort :high :max-tokens 512) req))))
  (is (not (contains? (model/request-body (assoc deepseek :thinking :disabled :reasoning-effort :low) req) :reasoning_effort)) "thinking off: no effort sent")
  (is (contract/valid? :seon.model/provider (assoc deepseek :thinking :enabled :reasoning-effort :low))))

(deftest ^{:seon.test/estimate {:ticks 1000000}} thinking-on-every-assistant-message-says-its-reasoning
  ;; docs/model.md#thinking-is-on-at-low-effort: DeepSeek answers 400 at the first request otherwise, whose first
  ;; message after the system's is an eval Seon inserted, which no reasoning made
  (let [inserted {:role "assistant" :content "Seon ran this." :tool-calls [{:id "eval-e1" :code "(+ 1 2)"}]}
        answered {:role "tool" :tool-call-id "eval-e1" :content "{}"}
        own {:role "assistant" :content "" :reasoning "Square it." :reply 1 :tool-calls [{:id "c1" :code "(+ 1 2)"}]}
        r (assoc req :messages [{:role "system" :content "s"} inserted answered own])
        reasoning (fn [p] (mapv #(find % :reasoning_content) (:messages (model/request-body p r))))]
    (is (= [nil [:reasoning_content ""] nil [:reasoning_content "Square it."]]
           (reasoning (assoc deepseek :thinking :enabled :replay-reasoning true))))
    (is (= [nil nil nil [:reasoning_content "Square it."]]
           (reasoning (assoc deepseek :thinking :disabled :replay-reasoning true))) "off, a request stays as it was: nothing added to its bytes")))

(deftest ^{:seon.test/estimate {:ticks 1000000}} a-reply-carries-what-it-cost-and-an-eaten-reply-is-a-problem
  (testing "content, with the usage DeepSeek reports"
    (let [c (model/parse-reply (clojure.data.json/write-str
                                 {:choices [{:message {:content "(+ 1 2)" :reasoning_content "think"}}]
                                  :usage {:prompt_tokens 100 :completion_tokens 40 :prompt_cache_hit_tokens 64
                                          :completion_tokens_details {:reasoning_tokens 30}}})
                               12.5 2048)]
      (is (= {:text "(+ 1 2)" :prompt-tokens 100 :completion-tokens 40 :reasoning-tokens 30 :cache-hit-tokens 64 :ms 12.5
              :reasoning "think"} c))))
  (testing "an empty content beside reasoning: thinking spent the budget"
    (let [e (model/parse-reply (clojure.data.json/write-str
                                 {:choices [{:message {:content "" :reasoning_content "long thoughts"}}]
                                  :usage {:prompt_tokens 100 :completion_tokens 2048 :completion_tokens_details {:reasoning_tokens 2048}}})
                               9.0 2048)]
      (is (= :seon.model/reasoning-ate-reply (:seon.error/type e)))
      (is (= [2048 2048 13] ((juxt :reasoning-tokens :max-tokens :reasoning-chars) e)))
      (is (contract/valid? :seon.model/failure e))
      (testing "and root counts it as an effect problem, like any failed effect"
        (is (= :effect (:class (err/occurrence e :effect {} 1))))))))

(deftest ^{:seon.test/estimate {:ticks 1000000}} a-reply-cut-at-max-tokens-is-marked-cut
  ;; T5's first real run: replies of exactly max-tokens whose code was cut, recorded nowhere
  (let [body (fn [finish] (clojure.data.json/write-str {:choices [{:message {:content "(defn f [x]" } :finish_reason finish}]
                                                        :usage {:prompt_tokens 10 :completion_tokens 4096}}))]
    (is (true? (:truncated? (model/parse-reply (body "length") 9.0 4096))))
    (is (nil? (:truncated? (model/parse-reply (body "stop") 9.0 4096))) "a reply that stopped on its own is not cut")))

(deftest ^{:seon.test/estimate {:ticks 5000000}} another-tasks-unfinished-proof-is-not-a-problem
  ;; T5's real run: one agent's test run reports every program test, so 210 rows named other
  ;; tasks' proofs not done yet (allowed reds); only the task's own proofs are its problems
  (ts/test-branch! (fn [_ conn]
    (d/transact conn [(ended "t/a" {:seon.task/proofs #{'app.t/a-test} :seon.task/outcome :cap
                                    :seon.task/failures {'app.t/a-test [{:type :fail :expected "1" :actual "2"}]
                                                         'app.t/other-test [{:type :fail :expected "3" :actual "4"}]}})])
    (is (= ['app.t/a-test] (mapv :test (get-in (problems/report @conn) [:classes :red :rows])))))))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-lost-task-is-a-death
  ;; task seon.render/one-owner-per-view-path (A1): the failed outcomes are the
  ;; declared set (:seon.status/failed), not a literal in the reader
  (let [db (:db-after (d/with (seeded 1 5)
                              [(ended "t/l" {:seon.task/branch :l :seon.task/outcome :lost
                                             :seon.task/agent {:seon.agent/key "b/a9" :seon.agent/ended-ms 9}})]))
        rows (get-in (problems/report db) [:classes :dead :rows])]
    (is (= :lost (:status (first (filter #(= "t/l" (:task %)) rows)))))
    (is (not-any? #(= "t/l" (:task %)) (get-in (problems/report db) [:classes :error :rows])))))

(deftest ^{:seon.test/estimate {:ticks 10000000}} a-merge-with-its-process-tests-unrun-is-an-open-problem
  ;; owner's ruling, 2026-10-23: a change that reaches process tests merges with them unrun
  ;; unless root asked; unknown stays visible, an open :unrun row, until each is recorded
  ;; passing (the reconciler's :gate records them): one passing of two, or a red, keeps it open
  (let [att (fn [extra] (:db-after (d/with (seeded 1 5)
                                           [(ended "t/u" (merge {:seon.task/branch :u :seon.task/kind :merge :seon.task/outcome :merged
                                                                 :seon.task/agent {:seon.agent/key "b/a9" :seon.agent/ended-ms 9}
                                                                 :seon.task/deferred {'seon.system-test/boots "d1" 'seon.check-test/clean nil}}
                                                                extra))])))
        rows #(get-in (problems/report %) [:classes :unrun :rows])]
    (is (= [["t/u" "merged with 2 process tests unrun" true]] (mapv (juxt :task :reason :open?) (rows (att {})))))
    (is (= 1 (count (rows (att {:seon.task/deferred-results {'seon.system-test/boots :pass}}))))
        "one of two recorded: still open")
    (is (= 1 (count (rows (att {:seon.task/deferred-results {'seon.system-test/boots :pass 'seon.check-test/clean :fail}}))))
        "a red among them: still open")
    (is (= [] (rows (att {:seon.task/deferred-results {'seon.system-test/boots :pass 'seon.check-test/clean :pass}})))
        "each passing: the row closes")
    (is (= [{:ns 'app.repl-only :reason "no :ns/file on the branch: its forms were never written to a file"}]
           (:unread (first (rows (att {:seon.task/deferred-results {'seon.system-test/boots :error 'seon.check-test/clean :error}
                                       :seon.task/gate-unread [{:ns 'app.repl-only :reason "no :ns/file on the branch: its forms were never written to a file"}]})))))
        "a gate that could not read a changed namespace names it, and why, on the open row")))

;; docs/source.md#a-merged-task-reaches-mains-files: a merged task whose entry main's files still declare is one :unsynced row naming
;; the files its changes touch, until a reviewed sync lands; the sync (my.root/sync-req, committed by the :git effect on a scratch
;; repository) puts those files from main and the task file without its entry on a branch from git's main, so the work is in git
;; whatever becomes of the store; the landing's seon.task/load-tx of that file (the entry gone) closes the row
(deftest ^{:seon.test/estimate {:ticks 15000000}} a-merged-task-is-unsynced-until-its-reviewed-sync-lands
  (let [db0 (world)
        [fid path] (first (sort (for [[id] (d/q '[:find ?id :where [_ :form/id ?id]] db0)
                                      :let [p (seon.source/ns-file db0 (symbol (first (str/split id #"/" 2))))] :when p]
                                  [id p])))
        decl {:seon.task/ns 't :seon.task/name "m" :seon.task/plan "p"}
        changed #{(sedn/encode [:form fid])}
        db (d/db-with db0 [(ended "t/m" {:seon.task/outcome :merged :seon.task/merged-t 7 :seon.task/declared (sedn/encode decl) :seon.task/changed changed})
                           (ended "t/n" {:seon.task/outcome :merged :seon.task/changed changed})
                           (ended "t/k" {:seon.task/outcome :done :seon.task/declared (sedn/encode (assoc decl :seon.task/name "k"))})])
        ;; the world's own tasks are made on main and in no file, so unsynced too: this test's are the t/ ones
        rows #(filterv (fn [r] (str/starts-with? (:task r) "t/")) (get-in (problems/health %) [:classes :unsynced :rows]))]
    (is (some? path) "the world holds a form whose namespace has a file")
    (is (= [["t/m" [path] true 't]] (mapv (juxt :task :paths :open? :owner-ns) (rows db)))
        "one row, naming its files; a merged task no file declares (made in the store) and a declared task not merged have none")
    (testing "the sync: main's text of its files and its task file without its entry, on a branch from git's main"
      (let [d (str (doto (java.io.File/createTempFile "seon-sync" "") (.delete) (.mkdirs)))
            git (fn [& args] (:out (apply sh/sh "git" "-C" d "-c" "user.name=t" "-c" "user.email=t@t" args)))
            entries "[{:seon.task/name \"k\" :seon.task/plan \"keep\"}\n {:seon.task/name \"m\"\n  :seon.task/plan \"p\"}]\n"
            tasks-dir (str d "/root-tasks")]
        (.mkdirs (java.io.File. tasks-dir)) (spit (str tasks-dir "/t.edn") entries)
        (git "init" "-q" "-b" "main") (spit (str d "/README") "r") (git "add" "README") (git "commit" "-q" "-m" "main")
        (git "checkout" "-q" "-b" "jolt/seon-merges") (spit (str d "/other") "o") (git "add" "other") (git "commit" "-q" "-m" "merges")
        (let [{:keys [tasks paths req not-unsynced]} (my.root/sync-req db ["t/m" "t/k"] tasks-dir)
              r (fx/git-commit {:req (assoc req :dir d)})]
          (is (= [["t/m"] [path]] [tasks paths]))
          (is (= [[:seon.system/nothing-to-sync "t/k"]] (mapv (juxt :seon.error/type :task) not-unsynced)) "a task not merged is named, not synced")
          (is (string? (get-in r [:value :sha])) (pr-str r))
          (is (= "jolt/sync-m" (:branch req)))
          (is (= (str/trim (git "rev-parse" "main")) (str/trim (git "rev-parse" "jolt/sync-m^"))) "its parent is git's main, not the checkout's HEAD")
          (is (= (get (seon.doc/files db [path]) path) (git "show" (str "jolt/sync-m:" path))) "the file as main holds it")
          (is (= "[{:seon.task/name \"k\" :seon.task/plan \"keep\"}]\n" (git "show" "jolt/sync-m:resources/seon/tasks/t.edn"))
              "the task file without its entry, every other byte kept")
          (is (str/includes? (git "log" "-1" "--format=%B" "jolt/sync-m") "Completes: t/m"))
          (is (= "" (git "show" "jolt/sync-m:other")) "nothing of the checkout's own branch"))))
    (is (= [] (rows (d/db-with db (seon.task/load-tx db {'t []}))))
        "landed, its file declares it no more: the row closes")))

(declare catch-forms)

(deftest ^{:seon.test/estimate {:ticks 5000000}} caused-is-what-the-branch-made-true
  ;; a catch site its page does not name (an :unnamed-site) is a derived row: what the branch made true is the rows head
  ;; has that base had not
  (let [base (catch-forms (world) 1)]
    (testing "an unchanged branch caused nothing, though base has an unnamed site of its own"
      (is (seq (get-in (problems/health base) [:classes :unnamed-site :rows])))
      (is (= #{} (problems/caused base base))))
    (testing "the branch added a site: it is the branch's; the one base had is not"
      (let [head (catch-forms base 2) made (problems/caused base head)]
        (is (= 1 (count made)))
        (is (str/includes? (first made) "app.cost/v2"))))))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-catch-its-page-does-not-name-is-a-law-problem
  ;; bin/laws catch's rule, from the compiler's facts: a live form carrying :form/catches, by
  ;; the var it defines, named by docs/failure-points.md's cited names
  (let [db0 (world)
        v 'app.calc/inc2
        fe (:db/id (:var/form (d/entity db0 [:var/name v])))
        ;; the test db's program is an example app; make its namespace one of Seon's src
        ns-e (:db/id (:var/ns (d/entity db0 (:e (first (d/datoms db0 :avet :var/name v))))))
        db1 (d/db-with db0 [[:db/add fe :form/catches "{:class Exception, :answers :expr}"] [:db/add ns-e :ns/file "src/app/m.clj"]])
        unnamed #(mapv (juxt :law :site) (get-in (problems/health %) [:classes :unnamed-site :rows]))]
    (is (= [] (unnamed db0)) "no catch, no site")
    (is (= [[:catch v]] (unnamed db1)) "a catch the page does not name")
    (testing "a namespace outside src (an agent's app, a test: a deftest's `is` expands to a catch) is no failure point"
      (is (= [] (unnamed (d/db-with db1 [[:db/add ns-e :ns/file "test/app/x_test.clj"]])))))
    (testing "a deftest is a test wherever it lives (its var's :test, the compiler's fact): one an agent defined in a namespace of src is no failure point"
      (is (= [] (unnamed (d/db-with db1 [[:db/add (pdb/eid db1 :var/name v) :var/meta :test]])))))
    (testing "the page names it: no problem, nothing written to close it"
      (let [page (str "# Failure points\n\n| failure | edge |\n|---|---|\n| x | `" v "` |\n")]
        (is (= [] (unnamed (d/db-with db1 (seon.doc/load-tx db1 "docs" {"docs/failure-points.md" page})))))))))

(deftest ^{:seon.test/estimate {:ticks 6000000}} a-root-on-a-stale-binary-is-a-problem
  ;; task seon.problems/a-root-on-a-stale-binary-is-a-problem, docs/health.md#a-stale-binary: a boot holds seon.system/stale-binary's
  ;; answer (is the binary current for the tree main was last brought to, by what differs reaching root's code) on root's source
  ;; fact (seon.system/booted-source), and health derives one :stale-binary row from it while it is there, gone at the next boot on a
  ;; current binary: computed at the boot, never here, no clock, never an import. Main moved by what its agents merged is no row
  (let [db0 (world)
        held {:root "/c" :files 1 :forms 1 :ms 1 :tree "t1"}
        in {:env "e" :tree "t" :config {:seon.config.agent/max-turns "40"}}
        cfg-in {:seon.config.agent/max-turns "41"}
        boot (fn [db b built main stale & [current-inputs]] (d/db-with db [{:seon.system/id :root :seon.system/source (root/booted-source held b built main cfg-in stale current-inputs)}]))
        source #(:seon.system/source (d/pull % [:seon.system/source] [:seon.system/id :root]))
        rows #(mapv (juxt :binary :tree) (get-in (problems/health %) [:classes :stale-binary :rows]))
        messages #(mapv :message (get-in (problems/health %) [:classes :stale-binary :rows]))
        stale (boot db0 "b1" {:program "p0" :inputs (assoc in :tree "t0")} "p1" {:binary "t0" :tree "t1" :reaches ['seon.writer/commit-batch]})]
    (is (= [] (rows db0)) "no source fact: nothing known, no row")
    (is (= [["t0" "t1"]] (rows stale)) "a binary not current for main's tree: one row naming both trees")
    (is (some #(str/includes? % "seon.writer/commit-batch") (messages stale)) "and what between them reaches root's own code")
    (is (nil? (:inputs (source stale))) "and no inputs to key agents' verdicts by")
    ;; task seon.system/every-reader-of-the-binarys-tree-asks-binary-current: a binary built from t0 and current for t1 by reach
    (is (= "t1" (get-in (source (boot db0 "b3" {:program "p0" :inputs (assoc in :tree "t0")} "p3" nil (assoc in :tree "t1"))) [:inputs :tree]))
        "a binary current for main's tree by reach, built from another, hands agents main's tree's inputs, never its own")
    (is (nil? (:inputs (source (boot db0 "b4" {:program "p0" :inputs (assoc in :tree "t0")} "p4" {:binary "t0" :tree "t1" :reaches ['x/y]} (assoc in :tree "t1")))))
        "and a stale one none")
    (is (= [[nil "t1"]] (rows (boot db0 "b1" nil "p1" {:binary nil :tree "t1" :missing :binary-tree}))) "a binary whose build recorded no tree is unknown, never current")
    (let [merged (boot stale "b2" {:program "p0" :inputs (assoc in :tree "t1")} "p2" nil)]
      (is (= [] (rows merged)) "a current binary, main moved by what its agents merged (p2): no row, the last boot's answer gone")
      (is (= (assoc in :tree "t1" :config cfg-in) (:inputs (source merged))) "its inputs, with the config in force, are what agents key verdicts by")
      (is (= ["b2" "p0" "p0" "t1"] ((juxt :boot :binary :program :binary-tree) (source merged)))
          "and its program the binary's, the one those inputs describe"))))

(deftest ^{:seon.test/estimate {:ticks 4000000}} a-pin-the-wiki-states-is-the-recorded-gitlink
  (let [db0 (world)
        page "# Forks\n\n## Jolt\n\n`reference-code/jolt` pinned at `4342b73c`, the tip of ``jolt/x``.\n\n## Chez\n\n`reference-code/chez` pinned at `80e4b9f`.\n"
        db (d/db-with db0 (seon.doc/load-tx db0 "docs" {"docs/forks.md" page}))
        pinned (fn [pins] (mapv (juxt :path :stated :recorded)
                                (get-in (problems/health (cond-> db pins (d/db-with [{:seon.system/id :root :seon.system/source {:root "/c" :files 1 :forms 1 :ms 1 :pins pins}}])))
                                        [:classes :stale-pin :rows])))]
    (is (= [] (pinned nil)) "no import recorded: unknown, not a mismatch")
    (is (= [] (pinned {"reference-code/jolt" "4342b73cdeadbeef" "reference-code/chez" "80e4b9f0"})))
    (is (= [["reference-code/chez" "80e4b9f" "d19eb3b0"]] (pinned {"reference-code/jolt" "4342b73cdeadbeef" "reference-code/chez" "d19eb3b0"})))
    (is (= [["reference-code/jolt" "4342b73c" nil]] (pinned {"reference-code/chez" "80e4b9f0"})) "a pin with no gitlink")))

(deftest ^{:seon.test/estimate {:ticks 2000000}} an-error-closes-when-its-subject-changes
  ;; a recorded error may name :seon.error/subject; it is never retracted, and closes when its subject changes
  (let [db0 (world)
        row #(first (get-in (problems/report %) [:classes :platform :rows]))]
    (testing "a named subject: open until a fact of the var is written after the error"
      (let [v 'app.calc/inc2
            e (pdb/eid db0 :var/name 'app.calc/inc2)
            err (d/db-with db0 [{:seon.error/signature "bbbbbbbbbbbbbbbb" :seon.error/class :platform :seon.error/count 1
                                 :seon.error/agents 0 :seon.error/first-ms 1 :seon.error/last-ms 1 :seon.error/subject v}])]
        (is (some? (row err)) "the error has a row") (is (not (false? (:open? (row err)))))
        (is (false? (:open? (row (d/db-with err [{:db/id e :var/doc "changed"}])))))))))

;; ---- cost proofs of the derived classes ---------------------------------------------------

(defn catch-forms
  "db0 with n more live forms in a src namespace, each defining one var and carrying a catch the
  law page does not name (so each is an :unnamed-site)."
  [db0 n]
  (let [ns-e (:db/id (:var/ns (d/entity db0 [:var/name 'app.calc/inc2])))]
    (d/db-with db0 (into [[:db/add ns-e :ns/file "src/app/m.clj"]]
                         (mapcat (fn [i] [{:db/id (- (inc (* 2 i))) :form/id (str "cost-form-" i) :form/ns ns-e :form/order i :form/src "(defn f [])"
                                           :form/catches #{"{:class Exception, :answers :expr}"} :form/defines [(- (* 2 i))]}
                                          {:db/id (- (* 2 i)) :var/name (symbol "app.cost" (str "v" i)) :var/ns ns-e :var/form (- (inc (* 2 i)))}]))
                         (range 1 (inc n))))))

(defn catch-input "The cost proof's input at n: n unnamed catch sites." [n] [(catch-forms (world) n) {}])

(defn health-input "The cost proof's input at n: the db of n unnamed catch sites." [n] [(catch-forms (world) n)])

(defn pins-input
  "The cost proof's input at n: docs/forks.md of n sections each stating a pin, root's import
  recording a different gitlink for every one (so each is a :stale-pin)."
  [n] (let [db0 (world)
            page (apply str "# Forks\n\nThe pins.\n" (for [i (range n)] (str "\n## F" i "\n\n`reference-code/lib" i "` pinned at `abc" i "`.\n")))
            db (d/db-with db0 (seon.doc/load-tx db0 "docs" {"docs/forks.md" page}))]
        [(d/db-with db [{:seon.system/id :root :seon.system/source {:pins (into {} (for [i (range n)] [(str "reference-code/lib" i) (str "def" i)]))}}]) {}]))

(deftest ^{:seon.test/estimate {:ticks 20000000}} unnamed-sites-follow-the-catch-forms
  (cost/proof {:of 'seon.problems/unnamed-sites :input 'seon.problems-test/catch-input
               :n "live forms carrying a catch, each defining one var, none named by the page"
               :work "once: the page's cited names as a set, the method and impl owners, the program's namespaces (seon.program/program-nss); per site: its form entity, its defines and one row"
               :growth :linear
               ;; fixed calls 5 -> 9 (39 measured at n=10, 35 before): the world's test namespace app.calc-test has two
               ;; forms carrying a catch (a deftest's `is`), each read and dropped as no program namespace's: 2 calls each
               :per-n {:ns 76000 :bytes 74500 :calls 3}
               :fixed {:ns 50000 :bytes 50000 :calls 9}
               :sizes [10 30 100]}))

(deftest ^{:seon.test/estimate {:ticks 15000000}} stale-pins-follow-the-pin-page
  (cost/proof {:of 'seon.problems/stale-pins :input 'seon.problems-test/pins-input
               :n "sections of docs/forks.md, each stating one stale pin"
               :work "one AVET range of addresses, then per section a pull of its text, one regex scan and one row"
               :growth :linear
               :per-n {:ns 30000 :bytes 30000 :calls 2}
               :fixed {:ns 600000 :bytes 600000 :calls 5}
               :sizes [10 30 100]}))

(deftest ^{:seon.test/estimate {:ticks 30000000}} health-follows-what-is-recorded-and-cited
  (cost/proof {:of 'seon.problems/health :input 'seon.problems-test/health-input
               :n "unnamed catch sites (the derived classes dominate; the recorded part is the seed's)"
               :work "report once, then the three derived reads; the sites dominate"
               :growth :linear
               :per-n {:ns 80000 :bytes 80000 :calls 3}
               ;; fixed bytes re-derived 2026-10-23 (jolt/similar-finds-helpers-by-embedding): 3.01 MB at 10,
               ;; 10.0 MB at 100, 80.2 MB at 1,000 sites; a fixed part near the smallest size's whole cost read
               ;; as steeper growth (task seon.cost/a-speedup-never-reads-as-steeper)
               :fixed {:ns 19500000 :bytes 17500000 :calls 62}
               :sizes [10 30 100]}))

;; ---- caused reads what changed (diff by value), and agrees with the full reading ---------------

(defn- caused-sites
  "The :unnamed-site messages caused-split answers for head over base: what caused-full reads in full."
  [base head]
  (into (sorted-set) (comp (filter #(= :unnamed-site (:class %))) (map :message)) (:rows (problems/caused-split base head))))

(defn- caused-full
  "The old `caused`: both derived classes read in full over base and over head."
  [base head]
  (let [derived #(problems/unnamed-sites % {})
        before (into #{} (map :signature) (derived base))]
    (into (sorted-set) (comp (remove (comp before :signature)) (map :message)) (derived head))))

(deftest ^{:seon.test/estimate {:ticks 80000000}} caused-agrees-with-the-full-reading
  (let [db0 (world)
        [v w] ['app.calc/inc2 'app.calc/add]
        e (fn [db s] (pdb/eid db :var/name s))
        none (symbol (namespace v) "no-such-var")
        page (str "# P\n\nIt reads `" v "`, `" w "` and `" none "`.\n\n## Q\n\nAlso `" v "`.\n")
        base (d/db-with db0 (seon.doc/load-tx db0 "demo" {"demo/p.md" page}))
        base (catch-forms base 3)
        failure-points "# Failure points\n\n| x | `app.cost/v1` |\n"
        scenarios
        {"unchanged" base
         "a var both sections cite is deleted" (d/db-with base [[:db/retractEntity (e base v)]])
         "the var is back" (d/db-with (d/db-with base [[:db/retractEntity (e base v)]]) [{:var/name v}])
         "a section is added citing a dead name" (d/db-with base (seon.doc/load-tx base "demo" {"demo/p.md" page "demo/r.md" (str "# R\n\nIt reads `" none "`.\n")}))
         "a section is rewritten to cite a var that exists" (d/db-with base (seon.doc/load-tx base "demo" {"demo/p.md" (str "# P\n\nIt reads `" w "`.\n")}))
         "a catch site is added" (catch-forms base 4)
         "the failure-points page names a site" (d/db-with base (seon.doc/load-tx base "docs" {"docs/failure-points.md" failure-points}))
         "the page names it, then a var it cites is deleted" (let [b2 (d/db-with base (seon.doc/load-tx base "docs" {"docs/failure-points.md" failure-points}))]
                                                              (d/db-with b2 [[:db/retractEntity (e b2 'app.cost/v1)]]))}]
    (doseq [[label head] scenarios]
      (testing label
        (is (= (caused-full base head) (caused-sites base head)))
        (is (= (problems/caused base head)
               (problems/caused base head (seon.merge/delta-changes base head #{}) (diff base head)))
            "given the whole diff, the same rows: the datoms it holds beyond caused-datoms' are not read")))
    (testing "and the cases are not all empty"
      (is (seq (problems/caused base (get scenarios "a catch site is added")))))))

(defn caused-flat-input
  "The cost proof's input at n: [base head changes moved] over a program of n catch sites and n citing
  sections, the head one section rewritten to cite a dead name and one more catch site: a fixed
  change, its changes since base and the datoms that differ read as the turn and the writer hold them
  (seon.merge/delta-changes and the diff, read once: their cost is proved at
  seon.program-cost-test/a-turns-diff-reads-what-the-branch-changed)."
  [n] (let [db0 (world)
            vs (vec (take 10 (map :v (d/datoms db0 :aevt :var/name))))
            page (apply str "# Cited\n\nThe page.\n" (for [i (range n)] (str "\n## S" i "\n\nIt reads `" (nth vs (mod i (count vs))) "`.\n")))
            base (catch-forms (d/db-with db0 (seon.doc/load-tx db0 "demo" {"demo/c.md" page})) n)
            changed (str/replace page "## S0\n\nIt reads `" (str "## S0\n\nIt reads `app.nowhere/dead` and `"))
            head (catch-forms (d/db-with base (seon.doc/load-tx base "demo" {"demo/c.md" changed})) (inc n))]
        [base head (seon.merge/delta-changes base head #{}) (problems/caused-datoms base head)]))

(deftest ^{:seon.test/estimate {:ticks 50000000}} caused-follows-what-changed
  ;; the program grows (n sections citing, n catch sites); the change is fixed (one section, one
  ;; site): the work is the scan of the attributes' datoms, a few bytes each, never a pull or a rule
  ;; per citation or site. Before: ~150 KB per catch site, both dbs read in full. 24 contracted calls were its three
  ;; classes; 32 since split-rows (1) and a class read from the changed texts (6, since removed).
  ;; The input holds the branch's changes (ch), as a caller does: the 1.5 M ticks and 40 calls of the program diff are not caused's.
  (cost/proof {:of 'seon.problems/caused :input 'seon.problems-test/caused-flat-input
               :n "sections citing a var and catch sites in the program, one of each changed"
               :work "the datoms that differ (given), one entity seek per section, name, form and task they touch on each side, the rule for the candidates only; the sections, vars and catch sites the branch did not touch are not read"
               :growth :constant
               :per-n {}
               ;; measured 2026-10-29: ~0.45M ticks / 1.8 MB / 48 calls at 10, 100 and 1000 given the diff (a class read
               ;; from the changed texts on each side was most of it, since removed); before, 0.69M / 2.8 MB / 37 at 10 and linear (docs/cost.md#a-diff-per-attribute-costs-a-descent-each)
               ;; 68 calls at 10: :long-form (seon.problems/long-forms) is a class the changed vars are asked, with its bound read once (seon.config/value) each form's chars set against the bound's (seon.print.emit/chars-per-token), a long one's tokens estimated;
               ;; the 7 more contracted calls, each instrumented (its output checked), are the ticks and bytes the fixed part grew by (measured: the
               ;; seek and text of a var cost ~8k ticks of it)
               ;; and 4: the test law's two classes (unestimated-tests, undeclared-tests), one reader each on base and head (tests,
               ;; 2026-11-12: measured 69 -> 73); and 4 for data's two caused classes (unschemed-key, refused-value), one reader
               ;; each on base and head, the same shape (measured 73 -> 77 once both met in one tree)
               ;; and 8 for :unfocused-docstring (data, measured 77 -> 85): its reader and its bound on base and head, and a seek
               ;; and a row for each changed Seon var the tree holds
               :fixed {:ticks 460000 :bytes 1800000 :calls 85}
               :sizes [10 30 100]}))

;; ---- the wiki kept true: stale sections, orphan pages, issues with no page ----------------------

(defn- form-id-of [db v] (:form/id (:var/form (d/entity db (pdb/eid db :var/name v)))))
(defn- change-var
  "db with var v defined by another form (a new form with another id, as redefining it records one)."
  [db v]
  (let [ve (pdb/eid db :var/name v) f (:var/form (d/entity db ve))]
    (d/db-with db [{:db/id -1 :form/id (str "changed-" (random-uuid)) :form/src (:form/src f) :form/ns (:db/id (:form/ns f))
                    :form/order (:form/order f) :form/defines [ve]}
                   [:db/add ve :var/form -1]])))

(defn- change-meaning
  "db with var v's docstring (or, with :contract, its contract) changed, as redefining it with
  another docstring records it (seon.program/def-tx asserts the new value)."
  [db v & [attr]]
  (let [ve (pdb/eid db :var/name v)]
    (d/db-with (change-var db v) [[:db/add ve (or attr :var/doc) (str "changed " (random-uuid))]])))

(deftest ^{:seon.test/estimate {:ticks 15000000}} health-moved-is-health
  ;; health-moved carries prev's rows and reads where base and head differ: for each kind of move
  ;; of main it equals health read whole (set of rows by class; the row order is the scan's)
  (let [db0 (world)
        [v w] ['app.calc/inc2 'app.calc/add]
        page (str "# P\n\nIt reads `" v "` and `" w "`.\n")
        db1 (d/db-with db0 (seon.doc/load-tx db0 "demo" {"demo/p.md" page}))
        same? (fn [base head]
                (let [moved (problems/health-moved (problems/health base) base head (diff base head)) whole (problems/health head)]
                  (and (= (set (keys (:classes whole))) (set (keys (:classes moved))))
                       (every? #(= (set (get-in whole [:classes % :rows])) (set (get-in moved [:classes % :rows])))
                               (keys (:classes whole))))))
        died (ended "t/x" {:seon.task/proofs #{'t-test/x} :seon.task/outcome :dead :seon.task/reason "its agent died"
                           :seon.task/error (contract/error :seon.agent/died 'seon.system/end-agent "agent a1 died" {:agent :a1})})]
    (is (same? db1 db1) "nothing moved")
    (testing "a task whose agent died opens a problem, and a later wake that finished it closes it"
      (let [db2 (d/db-with db1 [died])
            db3 (d/db-with db2 [{:seon.task/id "t/x" :seon.task/outcome :merged}])]
        (is (same? db1 db2))
        (is (pos? (get-in (problems/health-moved (problems/health db1) db1 db2 (diff db1 db2)) [:classes :dead :n])))
        (is (same? db2 db3))
        (is (zero? (get-in (problems/health-moved (problems/health db2) db2 db3 (diff db2 db3)) [:classes :dead :n])))))
    (testing "a docstring changed under the text opens a stale section"
      (let [db2 (change-meaning db1 v)]
        (is (same? db1 db2))
        (is (= 1 (get-in (problems/health-moved (problems/health db1) db1 db2 (diff db1 db2)) [:classes :stale-section :n])))
        (testing "and editing the section closes it"
          (is (same? db2 (d/db-with db2 (seon.doc/load-tx db2 "demo" {"demo/p.md" (str page "\nMore.\n")})))))))
    (testing "a page added that an unchanged section links to"
      (let [linking (d/db-with db1 (seon.doc/load-tx db1 "demo" {"demo/p.md" (str page "\nSee [q](q.md).\n")}))
            both (d/db-with linking (seon.doc/load-tx linking "demo" {"demo/p.md" (str page "\nSee [q](q.md).\n") "demo/q.md" "# Q\n\nLead.\n"}))]
        (is (same? db1 linking))
        (is (same? linking both))))
    (testing "an answer added to an untouched task's row is carried current"
      (let [db2 (d/db-with db1 [died])
            db3 (d/db-with db2 [{:seon.task/id "fix/x" :seon.task/ns 'fix :seon.task/name "x" :seon.task/plan "p" :seon.task/execution :held
                                 :seon.task/branch :f :seon.task/kind :work :seon.task/evidence {:kind :error :from "t/x"}}])
            moved (problems/health-moved (problems/health db2) db2 db3 (diff db2 db3))
            whole (problems/health db3)]
        (is (= (get-in whole [:classes :dead :rows]) (get-in moved [:classes :dead :rows])))))))

(deftest ^{:seon.test/estimate {:ticks 10000000}} a-section-is-stale-when-the-code-it-cites-changed-after-its-text
  (let [db0 (world)
        [v w] ['app.calc/inc2 'app.calc/add]
        page (str "# P\n\nIt reads `" v "`.\n")
        db1 (d/db-with db0 (seon.doc/load-tx db0 "demo" {"demo/p.md" page}))
        stale #(get-in (problems/health %) [:classes :stale-section])
        read #(:seon.doc/read (d/pull % [:seon.doc/read] [:seon.doc/address "demo/p.md#p"]))]
    (testing "the loader records the form id of each var the text cites"
      (is (= {v (form-id-of db1 v)} (read db1))))
    (testing "an unchanged wiki writes nothing, the record kept"
      (is (= [] (seon.doc/load-tx db1 "demo" {"demo/p.md" page}))))
    (is (zero? (:n (stale db1))))
    (testing "a body changed, its contract and docstring not: not stale (owner, 2026-10-25, DECISIONS Dogfood rulings)"
      (is (zero? (:n (stale (change-var db1 v))))))
    (testing "a contract changed: stale"
      (let [r (first (:rows (stale (change-meaning db1 v :var/contract))))]
        (is (= ["demo/p.md#p" v [:var/contract]] ((juxt :section :name :changed) r)))))
    (let [db2 (change-meaning db1 v)]
      (testing "a docstring changed under the text: stale, naming the section, the var, what changed, was and is"
        (is (= 1 (:n (stale db2))))
        (let [r (first (:rows (stale db2)))]
          (is (= ["demo/p.md#p" v [:var/doc]] ((juxt :section :name :changed) r)))
          (is (= (:var/doc (d/entity db1 (pdb/eid db1 :var/name v))) (get-in r [:was :var/doc])))
          (is (= (:var/doc (d/entity db2 (pdb/eid db2 :var/name v))) (get-in r [:now :var/doc])))))
      (testing "reloading the same text does not forgive it: the record is kept while the text is unchanged"
        (is (= 1 (:n (stale (d/db-with db2 (seon.doc/load-tx db2 "demo" {"demo/p.md" page})))))))
      (testing "editing the section re-reads the code: closed"
        (is (zero? (:n (stale (d/db-with db2 (seon.doc/load-tx db2 "demo" {"demo/p.md" (str page "\nMore words.\n")})))))))
      (testing "a review closes it with the text untouched, and the review is kept"
        (let [db3 (d/db-with db2 (seon.doc/reviewed-tx db2 "demo/p.md#p" "sean"))]
          (is (zero? (:n (stale db3))))
          (is (= "sean" (:by (:seon.doc/reviewed (d/pull db3 [:seon.doc/reviewed] [:seon.doc/address "demo/p.md#p"])))))
          (is (= (:seon.doc/text (d/pull db2 [:seon.doc/text] [:seon.doc/address "demo/p.md#p"]))
                 (:seon.doc/text (d/pull db3 [:seon.doc/text] [:seon.doc/address "demo/p.md#p"])))))))
    (testing "it is attention, never a reason to refuse done: a branch that changes the code sees it, and the done rule does not"
      (let [head (change-meaning db1 v) {:keys [caused attention]} (problems/caused-split db1 head)]
        (is (= [] caused))
        (is (= 1 (count attention)))
        (is (re-find #"demo/p.md#p describes" (first attention)))
        (is (= #{} (problems/caused db1 head)))
        (is (contains? (:seon.problems/attention (contract/properties :seon.problems/class)) :stale-section))))))

(deftest ^{:seon.test/estimate {:ticks 4000000}} a-page-nothing-links-to-is-an-orphan-until-something-does
  (let [db0 (world)
        pages {"docs/README.md" "# Map\n\nThe map.\n\n- [a](a.md)\n" "docs/a.md" "# A\n\nThe page A.\n" "docs/b.md" "# B\n\nThe page B.\n"}
        db (d/db-with db0 (seon.doc/load-tx db0 "docs" pages))
        ;; the world's own page docs/calc.md is an orphan too (this load names only the test's pages); the test judges its own
        orphans #(into [] (comp (map :page) (remove #{"docs/calc.md"})) (get-in (problems/health %) [:classes :orphan-page :rows]))]
    (is (= ["docs/b.md"] (orphans db)) "the map is the root; a is linked by it; b by nothing")
    (let [linked (d/db-with db (seon.doc/load-tx db "docs" (assoc pages "docs/a.md" "# A\n\nSee [b](b.md).\n")))]
      (is (= [] (orphans linked)) "another page links it: closed, nothing written to close it"))
    (is (= [] (orphans (d/db-with db0 (seon.doc/load-tx db0 "docs" (dissoc pages "docs/README.md")))))
        "no map in the db: unknown, not every page an orphan")))

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-section-with-no-lead-is-a-problem
  (let [db0 (world)
        page "# A\n\nThe page A.\n\n## Listed\n\n- one\n- two\n\n## Said\n\nA sentence that says what it is.\n"
        db (d/db-with db0 (seon.doc/load-tx db0 "docs" {"docs/a.md" page}))
        rows #(mapv :section (get-in (problems/health %) [:classes :leadless-section :rows]))]
    (is (= ["docs/a.md#listed"] (rows db)) "a section whose first block is a list has no lead")
    (let [fixed (d/db-with db (seon.doc/load-tx db "docs" {"docs/a.md" (str/replace page "- one" "It holds two items.\n\n- one")}))]
      (is (= [] (rows fixed)) "a paragraph before the list closes it, with nothing else written"))
    (is (contains? (:seon.problems/derived (contract/properties :seon.problems/class)) :leadless-section))))

(defn leadless-input
  "The cost proof's input at n: n sections, none with a lead."
  [n] (let [db0 (world)]
        [(d/db-with db0 (seon.doc/load-tx db0 "docs" {"docs/p.md" (apply str "# P\n\nThe page.\n" (for [i (range n)] (str "\n## S" i "\n\n- item\n")))})) {}]))

(deftest ^{:seon.test/estimate {:ticks 15000000}} leadless-sections-follow-the-sections
  (cost/proof {:of 'seon.problems/leadless-sections :input 'seon.problems-test/leadless-input
               :n "sections, none with a lead"
               :work "the sections' addresses and the lead datoms once, sorted by address, then a row per section"
               :growth :linear
               :per-n {:bytes 8500 :calls 1}
               :fixed {:bytes 9000 :calls 1}
               :sizes [10 30 100]}))

(deftest ^{:seon.test/estimate {:ticks 4000000}} an-issue-with-no-page-is-a-problem
  (let [;; the world holds docs/calc.md; the test's first case is a db with no wiki, so its sections go
        db0 (let [db (world)] (d/db-with db (for [{:keys [e]} (d/datoms db :aevt :seon.doc/address)] [:db/retractEntity e])))
        err {:seon.error/signature "eeeeeeeeeeeeeeee" :seon.error/class :platform :seon.error/count 1 :seon.error/agents 0
             :seon.error/first-ms 1 :seon.error/last-ms 1 :seon.error/type :seon.fx/io-error}
        unlinked #(filterv #{:platform} (mapv :class (get-in (problems/health %) [:classes :problem-unlinked :rows])))
        with-err (d/db-with db0 [err])]
    (is (= [] (unlinked with-err)) "no wiki loaded: unknown")
    (let [wiki (d/db-with with-err (seon.doc/load-tx with-err "docs" {"docs/README.md" "# Map\n\nThe map.\n"}))]
      (is (= [:platform] (unlinked wiki)) "a wiki without the class's section: the issue points nowhere")
      (let [fixed (d/db-with wiki (seon.doc/load-tx wiki "docs" {"docs/README.md" "# Map\n\nThe map.\n" "docs/errors.md" "# Errors\n\n## Problems\n\nHow they are read.\n"}))]
        (is (= [] (unlinked fixed)) "the section exists: closed, nothing written")))))

(deftest ^{:seon.test/estimate {:ticks 100000000}} every-problem-class-points-at-a-section-of-the-real-wiki
  (let [docs (:seon.problems/docs (contract/properties :seon.problems/class))
        db0 (world)
        db (d/db-with db0 (seon.doc/load-dir-tx db0 (seon.config/checkout) "docs"))]
    (is (every? docs (:seon.problems/derived (contract/properties :seon.problems/class))) "each derived class declares its section")
    (doseq [[c a] docs]
      (is (some? (pdb/eid db :seon.doc/address a)) (str c " points at " a ", which is no section of the wiki")))))

(defn stale-input
  "The cost proof's input at n: n sections each citing a var, every var's docstring then changed (each section stale)."
  [n] (let [db0 (world)
            vs (vec (take n (map :v (d/datoms db0 :aevt :var/name))))
            page (apply str "# Cited\n\nThe page.\n" (for [i (range n)] (str "\n## S" i "\n\nIt reads `" (nth vs (mod i (count vs))) "`.\n")))
            db (d/db-with db0 (seon.doc/load-tx db0 "demo" {"demo/c.md" page}))]
        [(reduce change-meaning db (distinct vs)) {}]))

(deftest ^{:seon.test/estimate {:ticks 30000000}} stale-sections-follow-the-recorded-names
  (cost/proof {:of 'seon.problems/stale-sections :input 'seon.problems-test/stale-input
               :n "sections each citing a var whose docstring then changed"
               :work "the read, review, vars' and forms' datoms in one scan each, then per recorded name whose form moved a seek of its var, its name's history and each meaning attribute's history (value-at), and a row"
               :growth :linear
               ;; fixed calls 3 -> 18 (58 measured at n=10, 43 before): the world's own page docs/calc.md, whose two
               ;; sections cite four of the vars this input changes, so they are stale too and their recorded names
               ;; are read like the input's (about 4 calls a name, the per-n rate); ns and bytes are unchanged
               :per-n {:ns 90000 :bytes 120000 :calls 4}
               :fixed {:ns 2500000 :bytes 4000000 :calls 18}
               :sizes [10 30 100]}))

(defn orphan-input
  "The cost proof's input at n: the map and n pages, none linked."
  [n] (let [db0 (world)]
        [(d/db-with db0 (seon.doc/load-tx db0 "docs" (into {"docs/README.md" "# Map\n\nThe map.\n"} (for [i (range n)] [(str "docs/p" i ".md") (str "# P" i "\n\nThe page.\n")])))) {}]))

(deftest ^{:seon.test/estimate {:ticks 10000000}} orphan-pages-follow-the-sections
  (cost/proof {:of 'seon.problems/orphan-pages :input 'seon.problems-test/orphan-input
               :n "pages, none linked"
               :work "the sections' addresses and the reference datoms once, then a row per orphan"
               :growth :linear
               :per-n {:ns 30000 :bytes 30000 :calls 1}
               :fixed {:ns 500000 :bytes 500000 :calls 3}
               :sizes [10 30 100]}))

(defn unlinked-input
  "The cost proof's input at n: a db with a wiki, and every class holding n open rows."
  [n] (let [db0 (world)
            db (d/db-with db0 (seon.doc/load-tx db0 "docs" {"docs/README.md" "# Map\n\nThe map.\n"}))
            cs (:seon.problems/docs (contract/properties :seon.problems/class))]
        [db {} (into {} (for [c (keys cs)] [c {:n n :rows (vec (repeat n {:open? true}))}]))]))

(deftest ^{:seon.test/estimate {:ticks 5000000}} problem-unlinked-follows-the-classes-not-their-rows
  (cost/proof {:of 'seon.problems/problem-unlinked :input 'seon.problems-test/unlinked-input
               :n "open rows in each class"
               :work "one lookup per class with an open row, found at the first row"
               :growth :constant
               :per-n {:ns 0 :bytes 0 :calls 0}
               ;; 47: the :stalled class (seon.problems/stalled-agents) is one more class to look up;
               ;; 49: :flaky (seon.proofs/flaky) one more
               ;; 52: :long-form one more class to look up
               ;; 54: :stale-plan one more (its seon.db/eid and derived-row), 2026-11-10
               ;; 56: :unsynced one more (its seon.db/eid and derived-row), 2026-11-10
               ;; 58: :unschemed-key one more (its seon.db/eid and derived-row: this input gives every declared class open rows)
               ;; 60: :refused-value one more, the same two
               ;; 64: :unestimated-test and :undeclared-test two more (each its seon.db/eid and derived-row)
               ;; 66: :unfocused-docstring one more (its seon.db/eid and derived-row)
               :fixed {:ns 3000000 :bytes 4000000 :calls 66}
               :sizes [10 30 100]}))

(deftest ^{:seon.test/estimate {:ticks 4000000}} attention-survives-a-store-write
  ;; the turn records what it observed in its basis; a sorted set cannot be written to the store
  ;; (konserve's fasl), which ended every scripted turn's agent. caused-split answers vectors.
  (let [db0 (world)
        [v w] ['app.calc/inc2 'app.calc/add]
        base (d/db-with db0 (seon.doc/load-tx db0 "demo" {"demo/p.md" (str "# P\n\nIt reads `" v "`.\n")}))
        head (change-meaning base v)
        {:keys [caused attention]} (problems/caused-split base head)]
    (is (seq attention) "the branch made a section stale")
    (is (vector? attention))
    (is (vector? caused))
    (is (= {:attention attention :caused caused}
           (konserve.jolt.fasl/decode (konserve.jolt.fasl/encode {:attention attention :caused caused}))) "and it is written to the store and read back")))

(defn- uncited-fixture
  "A db of design/implement pairs for the uncited-task proofs: [db rows-fn]. Each pair is
  [name design-plan & more design keys]; every design step is a finished task (its implement step
  is ready) unless named in not-done."
  [pairs not-done]
  (let [db (world)
        ;; the example program's namespaces with their files, as a root's store records a program
        ;; namespace, so a plan citing one of its vars cites the program's (seon.program/program-names)
        db0 (d/db-with db (vec (for [n (d/q '[:find [?n ...] :where [_ :ns/name ?n]] db)]
                                 {:ns/name n :ns/file (str "src/" (str/replace (str n) "." "/") ".clj")})))
        decl (fn [n kind plan & {:as more}]
               (merge {:seon.task/ns 'app :seon.task/name n :seon.task/plan plan :seon.task/kind kind
                       :seon.task/proofs #{'app-test/a-proof}} more))
        pair (fn [[n plan & more]]
               [(apply decl n :design plan more)
                (decl (str n "-impl") :implement "" :seon.task/needs #{(str "app/" n)} :seon.task/proofs #{})])
        stub (str "(deftest stub (is false \"" seon.task/placeholder-assertion "\"))")
        done (first (:seon.status/finished (contract/properties :seon.task/outcome)))
        tx (seon.task/create-tx db0 (vec (mapcat pair pairs)))]
    (d/db-with db0 (concat tx [{:db/id -1 :var/name 'app-test/stub :var/form -2} {:db/id -2 :form/id "f-stub" :form/src stub}
                               {:db/id -3 :var/name 'app-test/real :var/form -4} {:db/id -4 :form/id "f-real" :form/src "(deftest real (is (= 1 1)))"}]
                              (for [[n] pairs :when (not (not-done n))]
                                {:seon.task/id (str "app/" n) :seon.task/outcome done})))))

(defn uncited-input
  "The cost proof's input at n tasks: n/2 design steps (a finished task, a plan citing a var) and an
  implement step each, the implement steps ready and fine."
  [n] (let [v 'app.calc/inc2]
        [(uncited-fixture (for [i (range (quot n 2))] [(str "t" i) (str "It changes `" v "`.")]) #{}) {}]))

(deftest ^{:seon.test/estimate {:ticks 40000000}} uncited-tasks-cost-the-tasks-they-examine
  ;; Sean, 2026-11-10: n restated as tasks examined (it was design/implement pairs): every task now gets its ready check, so
  ;; the class's work is per task; 68 calls measured at 20 tasks against 85 (per-n 4, fixed 5, unchanged)
  (cost/proof {:of 'seon.problems/uncited-tasks :input 'seon.problems-test/uncited-input
               :n "tasks examined: finished design steps and the ready implement steps that need them, each citing"
               :work "the kind and task datoms once, then each implement step's needs walked once; whether any cite exists along them (one seek a task; until 2026-11-10 the program's vars among the cites, asked of seon.program/program-names)"
               :growth :linear
               :per-n {:ns 50000 :bytes 65000 :calls 4}
               :fixed {:ns 500000 :bytes 500000 :calls 5}
               :sizes [20 60 200]}))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-ready-implement-task-that-cites-nothing-is-a-problem
  ;; the context is only as good as the plan it is built from: an implement step whose accumulated
  ;; plan (its design step's) cites no var, attribute or section reaches no reference, so the walk
  ;; pulls nothing (task seon.render/spend-has-a-render, 2026-10-23). A design step's text plan is
  ;; its input, never red.
  (let [v 'app.calc/inc2
        cited (str "It changes `" v "`.")
        pairs [["cites" cited] ["text" "Something should be shown somewhere."]
               ["stubbed" cited :seon.task/proofs #{'app-test/stub}] ["bare" cited :seon.task/proofs #{}]
               ["waits" "Text, but its design step is not done."] ["real" cited :seon.task/proofs #{'app-test/real}]]
        db (uncited-fixture pairs #{"waits"})
        rows #(into #{} (map (juxt (comp :task :evidence) (comp :reason :evidence))) (get-in (problems/health %) [:classes :uncited-task :rows]))]
    (is (= #{["app/text-impl" :no-references] ["app/stubbed-impl" :placeholder-proof] ["app/bare-impl" :no-proofs] ["app/waits" :no-references]} (rows db))
        "a step citing nothing, one whose proof is the stub, one with no proof, and a ready step whose plan is text, whatever its kind; a finished step and one whose needs are not done are not judged; a real proof is fine")
    (testing "each closes with nothing written to close it"
      (let [fixed [["cites" cited] ["text" (str "Show `" v "` somewhere.")] ["stubbed" cited :seon.task/proofs #{'app-test/real}]
                   ["bare" cited :seon.task/proofs #{'app-test/real}] ["waits" "Text, but its design step is not done."] ["real" cited :seon.task/proofs #{'app-test/real}]]]
        (is (= #{["app/waits" :no-references]} (rows (uncited-fixture fixed #{"waits"}))) "only the ready step still citing nothing")))
    (is (contains? (:seon.problems/derived (contract/properties :seon.problems/class)) :uncited-task))))

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-plan-goes-stale-when-what-it-cites-changes
  ;; in a real run two agents spent evals because a plan, written before a function existed, said it was missing
  ;; (docs/health.md#a-plan-that-went-stale)
  (let [v 'app.calc/inc2
        redefine [{:var/name v :var/form -1} {:db/id -1 :form/id "f-inc2-new" :form/src "(defn inc2 [x] (+ x 2))"}]
        db0 (world)
        planned (d/db-with db0 (seon.task/create-tx db0 [{:seon.task/ns 'app :seon.task/name "plan" :seon.task/kind :whole :seon.task/proofs #{'app-test/real}
                                                          :seon.task/plan (str "It changes `" v "`.")}]))
        rows #(mapv :evidence (get-in (problems/health %) [:classes :stale-plan :rows]))]
    (is (= [] (rows planned)) "a var defined before the plan: no row")
    (let [moved (d/db-with planned redefine)]
      (is (= [{:kind :stale-plan :task "app/plan" :var v}] (rows moved)) "the var redefined after the plan: one row naming both")
      (is (= [] (rows (d/db-with moved [{:seon.task/id "app/plan" :seon.task/plan (str "It changes `" v "`, as it now is.")}])))
          "the plan written again closes it"))))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-ready-task-of-any-kind-that-cites-nothing-is-a-problem
  ;; the class judged only :implement tasks, and no task on the store had that kind (all :whole, 2026-11-10): a check keyed on a
  ;; kind matched nothing and never fired. A ready task is judged by its plan, whatever its kind
  (let [db (world)
        db (d/db-with db (seon.task/create-tx db [{:seon.task/ns 'app :seon.task/name "tile" :seon.task/kind :whole :seon.task/proofs #{'app-test/real}
                                                   :seon.task/plan "The root tile shows what boot loaded."}]))
        rows (into #{} (map (juxt (comp :task :evidence) (comp :reason :evidence))) (get-in (problems/health db) [:classes :uncited-task :rows]))]
    (is (contains? rows ["app/tile" :no-references]) "a ready :whole task whose plan names nothing the context can pull")))

(deftest ^{:seon.test/estimate {:ticks 8000000}} a-cite-ends-with-its-target-and-its-task-shows-what-is-gone
  ;; docs/storage.md#the-write-checks-every-entity-it-touches, docs/references.md#task-references: an import that retracts a var
  ;; a task cites left the cite without its required target, so the write check refused the whole import (2026-11-10:
  ;; generated slow-test tasks citing tests, and root on main's older code could not boot). The cite ends with its target on
  ;; the one path, and that write leaves the task :seon.task/gone naming it, which health reads (a datom, no parse)
  (let [db (t/with (world) (t/task 'app.calc "gone" "Make `app.calc/inc2` add two exactly." #{'app.calc-test/total-sums}))
        task #(d/pull % [:seon.task/gone] [:seon.task/id "app.calc/gone"])
        cites #(set (map seon.task/cited-name (seon.task/cites-of % (pdb/eid % :seon.task/id "app.calc/gone"))))
        files (into {} (for [n ['app.report 'app.calc-test]] [(:ns/file (d/entity db [:ns/name n])) (seon.source/file-text db n)]))
        calc (str/replace (seon.source/file-text db 'app.calc) #"\n\n\(defn inc2[^\n]*\n" "\n")
        _ (is (contains? (cites db) 'app.calc/inc2) "the task cites the var")
        _ (is (not (str/includes? calc "inc2")) "the file the import reads no longer defines it")
        after (t/with db (seon.source/replace-tx db (assoc files "src/app/calc.clj" calc)))
        rows (fn [db] (filter #(= "app.calc/gone" (:task %)) (get-in (problems/health db) [:classes :uncited-task :rows])))]
    (is (nil? (pdb/eid after :var/name 'app.calc/inc2)) "the import is accepted and the var is gone")
    (is (not (contains? (cites after) 'app.calc/inc2)) "its cite ended with it")
    (is (empty? (d/datoms after :avet :seon.cite/id "app.calc/gone app.calc/inc2")) "nothing of the cite is left")
    (is (= ['app.calc/inc2] (:seon.task/gone (task after))) "the write that ended it left the task what is gone")
    (is (= #{[:gone ['app.calc/inc2]] [:no-references nil]} (set (map (juxt :reason :gone) (rows after))))
        "the task shows that its plan names what no longer exists (and, its one cite gone, that it cites nothing the context pulls)")
    (is (empty? (rows db)) "before the import it was no problem")
    (let [plan "Make ``app.calc/inc2`` add two again, once it is written."
          fixed (t/with after (into [[:db/add [:seon.task/id "app.calc/gone"] :seon.task/plan plan]]
                                    (seon.task/rederive-tx after {:seon.task/id "app.calc/gone" :seon.task/plan plan})))]
      (is (nil? (:seon.task/gone (task fixed))) "the plan rewritten without the name clears the fact")
      (is (not-any? #(= :gone (:reason %)) (rows fixed)) "and the row"))))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-task-citing-a-section-cites-something
  ;; the standing root, 2026-11-10: 100 of 132 :no-references rows cited sections, files, schema keys or a dependency's vars, which
  ;; the context pulls; only a cite of a program var counted. Any cite counts: each names what exists, a dead one refused at the write
  (let [db0 (world)
        db0 (d/db-with db0 (seon.doc/load-tx db0 "docs" {"docs/p.md" "# P\n\nIt says what it is about.\n"}))
        db (d/db-with db0 (seon.task/create-tx db0 [{:seon.task/ns 'app :seon.task/name "doc" :seon.task/kind :whole :seon.task/proofs #{'app-test/real}
                                                     :seon.task/plan "Change what `docs/p.md#p` says."}]))
        e (:db/id (d/entity db [:seon.task/id "app/doc"]))
        rows (into #{} (map (juxt (comp :task :evidence) (comp :reason :evidence))) (get-in (problems/health db) [:classes :uncited-task :rows]))]
    (is (seq (d/datoms db :avet :seon.cite/of e)) "the plan's cite of the section is written")
    (is (not (contains? rows ["app/doc" :no-references])) "a task citing a section is not one that cites nothing")))

(defn- var-forms
  "db0 with the vars app.cite/v0..: each in a src namespace, its form src, docstring and kind as given
  by (spec i) -> {:src :doc :kind}."
  [db0 n spec]
  (let [ns-e (:db/id (:var/ns (d/entity db0 [:var/name 'app.calc/inc2])))
        nsym (:ns/name (d/entity db0 ns-e))]
    (d/db-with db0 (into [[:db/add ns-e :ns/file "src/app/cite.clj"] [:db/add ns-e :ns/doc "What the cite namespace does. See docs/health.md#a-function-that-cites-no-section."]]
                         (mapcat (fn [i] (let [{:keys [src doc meta refs]} (spec i)]
                                           [(cond-> {:db/id (- (inc (* 2 i))) :form/id (str "cite-form-" i) :form/ns ns-e :form/order i :form/src src :form/defines [(- (* 2 i))]}
                                              refs (assoc :form/refs refs))
                                            (cond-> {:db/id (- (* 2 i)) :var/name (symbol (str nsym) (str "v" i)) :var/ns ns-e :var/form (- (inc (* 2 i))) :var/meta (or meta #{:arglists})}
                                              doc (assoc :var/doc doc))]))
                                 (range 1 (inc n)))))))

;; See docs/health.md#an-unfocused-docstring
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-docstring-that-says-more-than-its-var-is-unfocused
  (let [words (fn [n] (str/join " " (repeat n "word")))
        db (d/db-with (world) [{:var/name 'seon.focus/long :var/doc (words 60)}
                               {:var/name 'seon.focus/short :var/doc "Answers the task's id."}
                               {:var/name 'seon.focus/measured :var/doc "Answers the plan. Measured at 12 ms."}
                               {:var/name 'seon.focus/dated :var/doc "Answers the plan, as ruled 2026-11-13."}
                               {:var/name 'seon.focus/twice :var/doc "Answers the plan (docs/tasks.md#a). See docs/tasks.md#b."}
                               {:var/name 'app.calc/wordy :var/doc (words 60)}])
        rows #(into {} (map (fn [{{:keys [var why]} :evidence}] [(symbol (name var)) why]))
                   (problems/unfocused-docstrings db {} %))]
    (is (= '{long [:words] measured [:measurement] dated [:date] twice [:wiki-twice]}
           (rows '#{seon.focus/long seon.focus/short seon.focus/measured seon.focus/dated seon.focus/twice}))
        "over the bound, a measurement, a date, a page cited twice: each its reason; a focused one none")
    (is (= {} (rows '#{app.calc/wordy})) "a program's own namespace is not Seon's to judge")
    (is (= {} (rows #{})) "no changed var, nothing read")
    (is (contains? seon.problems/caused-classes :unfocused-docstring))
    (is (contains? (:seon.problems/attention (contract/properties :seon.problems/class)) :unfocused-docstring) "shown, never refused")))

(defn- cite-ns "The namespace var-forms' vars are in: the test db's first var's." [db0]
  (str (:ns/name (:var/ns (d/entity db0 [:var/name 'app.calc/inc2])))))

(defn uncited-var-input
  "The cost proof's input at n: n public vars with a docstring citing nothing, named for the check."
  [n] [(var-forms (world) n (fn [_] {:src "(defn f [])" :doc "Does a thing."})) {}
       (into #{} (map #(symbol (cite-ns (world)) (str "v" %))) (range 1 (inc n)))])

;; See docs/health.md#a-function-that-cites-no-section
(deftest ^{:seon.test/estimate {:ticks 6000000}} a-public-var-cites-the-section-that-describes-it
  (let [db (var-forms (world) 7
                      (fn [i] (case (int i)
                                7 {:src "(seon.contract/defn n [])" :doc "Does a thing."}
                                1 {:src "(defn f [])" :doc "Does a thing."}
                                2 {:src "(defn g [])" :doc "Does a thing. See docs/health.md#a-stale-section."}
                                3 {:src "(defn- h [])" :doc "A private thing."}
                                4 {:src "(defn k [])"}
                                5 {:src "(deftest t [])" :doc "A test." :meta #{:test}}
                                6 {:src "(defn m [])" :doc "See docs/errors.md#problems."})))
        v #(symbol (cite-ns db) (str "v" %))
        rows #(mapv (comp :var :evidence) (problems/uncited-vars db {} (into #{} (map v) %)))]
    (is (= [(v 1) (v 4) (v 7)] (rows [1 2 3 4 5 6 7])) "a documented public var citing nothing and an undocumented one, and one defined by a namespaced defn; a cited one, a private one and a test are not")
    (is (= [] (problems/uncited-vars db {} #{})) "no changed var, nothing read")
    (is (= [] (problems/uncited-vars db {} #{'app.nowhere/x})) "a name no var has")
    (testing "attention: a branch that adds an uncited public var is told, never refused (docs/processes.md#an-agent-is-never-gated), and a var the base already left uncited is not its problem"
      (let [base (var-forms (world) 1 (fn [_] {:src "(defn f [])" :doc "Does a thing."}))
            head (var-forms (world) 2 (fn [i] {:src "(defn f [])" :doc "Does a thing."}))
            split (problems/caused-split base head)]
        (is (some #(re-find #"/v2 is public" %) (:attention split)))
        (is (empty? (:caused split)) "and it refuses nothing")
        (is (not-any? #(re-find #"/v1 is public" %) (:attention split)))))
    (is (contains? (:seon.problems/derived (contract/properties :seon.problems/class)) :uncited-var))))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-persons-code-owes-no-wiki-citation
  ;; the request measurement, 2026-11-10: an agent adding demo.math/triple for a person spent its turns finding a section to cite,
  ;; because the write-back gave demo.math a file under src/ and the class judged it as the program's. The wiki describes Seon's own
  ;; namespaces, each docstring citing its section; a person's namespace cites none, and its code owes none
  (let [db0 (var-forms (world) 1 (fn [_] {:src "(defn f [])" :doc "Does a thing."}))
        ns-e (:db/id (:var/ns (d/entity db0 [:var/name 'app.calc/inc2])))
        v (symbol (cite-ns db0) "v1")
        persons (d/db-with db0 [[:db/retract ns-e :ns/doc (:ns/doc (d/entity db0 ns-e))]])]
    (is (= [v] (mapv (comp :var :evidence) (problems/uncited-vars db0 {} #{v}))) "a namespace the wiki describes: its uncited var is a row")
    (is (= [] (problems/uncited-vars persons {} #{v})) "the same var in a namespace whose docstring cites no section, a person's: no row")))

;; See docs/health.md#a-function-that-cites-no-section
(deftest ^{:seon.test/estimate {:ticks 20000000}} uncited-vars-follow-the-vars-asked-for
  (cost/proof {:of 'seon.problems/uncited-vars :input 'seon.problems-test/uncited-var-input
               :n "changed public vars, each with a docstring that cites nothing"
               :work "once: the program's namespaces (seon.program/program-nss); per named var: one AVET seek, its entity, its docstring and its form's head (read by seon.source/first-head), and one row"
               :growth :linear
               ;; measured 2026-10-27: 130k ticks / 406 KB / 22 calls at 10, 3.87M / 11.9 MB / 602 at 300;
               ;; 2026-10-29, the form's head read by seon.source/first-head (one contracted call per var): 32 calls at 10;
               ;; 2026-11-01, the head read by seon.reader (edamame: `::alias/kw` without the namespace loaded),
               ;; read-all and opts two more calls a var: 55 calls, 210k ticks, 593 KB at 10
               :per-n {:ticks 20000 :bytes 57000 :calls 5}
               :fixed {:ticks 10000 :bytes 30000 :calls 5}
               :sizes [10 30 100]}))


(defn long-form-input
  "The cost proof's input at n: n changed vars whose form is under the bound, named for the check."
  [n] [(var-forms (world) n (fn [_] {:src "(defn f [])" :doc "Does a thing."}))  {}
       (into #{} (map #(symbol (cite-ns (world)) (str "v" %))) (range 1 (inc n)))])

;; See docs/health.md#a-form-too-long
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-form-over-the-bound-is-a-long-form-until-it-is-split
  (let [bound (seon.config/value :seon.config.shrink/form-tokens)
        pad #(str "(defn f [] \"" (apply str (repeat % \x)) "\")")
        long-src (pad (* 4 bound)) short-src (pad 10)
        db (var-forms (world) 3 (fn [i] (case (int i) 1 {:src long-src} 2 {:src short-src} 3 {:src long-src :meta #{:test}})))
        v #(symbol (cite-ns db) (str "v" %))
        rows #(mapv :evidence (problems/long-forms % {} (into #{} (map (partial symbol (cite-ns %))) ["v1" "v2" "v3"])))]
    (is (= [{:kind :long-form :var (v 1) :tokens (seon.print.emit/chars-tokens (count long-src)) :bound bound} {:kind :long-form :var (v 3) :tokens (seon.print.emit/chars-tokens (count long-src)) :bound bound}]
           (rows db))
        "a form over the bound has its row with its chars, in a test as in source; one under it has none")
    (is (= [] (rows (var-forms (world) 3 (fn [_] {:src short-src})))) "split under the bound, no row")
    (is (= [] (problems/long-forms db {} #{})) "no changed var, nothing read")
    (testing "a branch that adds a long form is shown it, never refused; one the base already had is not its problem"
      (let [base (var-forms (world) 1 (fn [_] {:src long-src}))
            head (var-forms (world) 2 (fn [_] {:src long-src}))
            split (problems/caused-split base head)]
        ;; attention, never a reason to refuse done (get out of an agent's way: docs/processes.md#an-agent-is-never-gated): its task shrinks it
        (is (some #(re-find #"/v2 is \d+ tokens" %) (map str (:attention split))))
        (is (not-any? #(re-find #"/v2 is \d+ tokens" %) (map str (:caused split))) "never refused")
        (is (not-any? #(re-find #"/v1 is \d+ tokens" %) (map str (:attention split))))))
    (is (contains? (:seon.problems/derived (contract/properties :seon.problems/class)) :long-form))))

;; See docs/health.md#a-form-too-long
(deftest ^{:seon.test/estimate {:ticks 15000000}} long-forms-follow-the-vars-asked-for
  (cost/proof {:of 'seon.problems/long-forms :input 'seon.problems-test/long-form-input
               :n "changed vars, each with a form under the bound"
               :work "per named var: one AVET seek, its entity and its form's text length"
               :growth :linear
               :per-n {:ticks 13000 :bytes 39500 :calls 3}
               :fixed {:ticks 10000 :bytes 30000 :calls 5}
               :sizes [10 30 100]}))

;; See docs/health.md#a-proof-that-passes-by-waiver
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-proof-whose-form-carries-an-overrun-is-a-waived-proof-until-it-is-deleted
  (let [proof (fn [overrun] (str "(deftest p [] (cost/proof {:of 'a/b :input 'a/c :n \"x\" :work (str \"w\") :growth :linear"
                                 " :per-n {:ticks 1000 :bytes 100} :sizes [10 30 100]" overrun "}))"))
        world-of (fn [overrun] (var-forms (world) 2 (fn [i] (case (int i)
                                                              1 {:src (proof overrun) :meta #{:test} :refs #{'seon.cost/proof}}
                                                              2 {:src "(defn f [] 1)"}))))
        waiver " :overrun {:task \"a/task\" :n 100 :measured {:ticks 300000.0 :bytes 20000.0 :ns 1.0}}"
        rows #(mapv (juxt :test :task :n :multiple) (problems/waived-proofs % {}))
        db (world-of waiver)
        t (symbol (cite-ns db) "v1")]
    (is (= [[t "a/task" 100 3.0]] (rows db)) "one row: the test, its task, the size, the worst counted dimension's multiple of the bound at that size (ticks 300000 over 1000 x 100)")
    (is (= [[t "a/task" 100 3.0]] (mapv (juxt :test :task :n :multiple) (get-in (problems/health db) [:classes :waived-proof :rows]))) "health shows it")
    (is (= [] (rows (world-of ""))) "deleting the :overrun removes the row")
    (is (= [] (rows (var-forms (world) 1 (fn [_] {:src (proof waiver) :meta #{:test}})))) "a form that does not call seon.cost/proof is not read")
    (is (contains? (:seon.problems/derived (contract/properties :seon.problems/class)) :waived-proof))))

;; See docs/health.md#a-proof-that-passes-by-waiver
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-branch-that-adds-an-overrun-is-caused-the-waived-proof
  ;; the owner, 2026-11-12: nothing switched off, sampled or waived. A waiver is a branch's content, so a branch that adds an
  ;; :overrun to a proof is caused the :waived-proof row (it was listed by health and never judged on a branch until then),
  ;; and one that deletes it closes it
  (let [proof (fn [overrun] (str "(deftest p [] (cost/proof {:of 'a/b :input 'a/c :n \"x\" :work (str \"w\") :growth :linear"
                                 " :per-n {:ticks 1000 :bytes 100} :sizes [10 30 100]" overrun "}))"))
        world-of (fn [overrun] (var-forms (world) 1 (fn [_] {:src (proof overrun) :meta #{:test} :refs #{'seon.cost/proof}})))
        waiver " :overrun {:task \"a/task\" :n 100 :measured {:ticks 300000.0 :bytes 20000.0}}"
        without (world-of "") with (world-of waiver)
        waived (fn [split k] (filterv #(= :waived-proof (:class %)) (k split)))]
    (is (seq (waived (problems/caused-split without with) :rows)) "adding an :overrun is caused")
    (is (seq (waived (problems/caused-split with without) :closed)) "deleting it closes the row")
    (is (contains? seon.problems/caused-classes :waived-proof))))

(defn- law-ns
  "The world with namespace app.law-test of forms (strings, one a line)."
  [& forms]
  (t/with (world) (t/code 'app.law-test (apply str "(ns app.law-test (:require [clojure.test :refer [deftest is]] [seon.test-store :as ts] [datahike.api :as d]))\n"
                                               (map #(str % "\n") forms)))))

(deftest ^{:seon.test/estimate {:ticks 60000000}} a-changed-test-states-what-its-type-needs
  ;; docs/testing.md#test-types: a test the branch added or changed that reaches a root (directly or through its namespace's
  ;; helper) naming none, or calls a store of its own is a caused :undeclared-test, one row a lack (stating no estimate is
  ;; :unestimated-test's); declared, none but an estimate over its cap; an unchanged undeclared test is not judged. Work: the world (cached), three imports of a
  ;; six-form namespace (~5 M ticks each) and two caused-splits over the few vars they change.
  (let [old "(deftest old (is true))"
        base (law-ns old)
        undeclared (law-ns old "(deftest t (ts/start-root! {}))" "(defn- go [] (ts/start-root! {}))" "(deftest h (go))" "(deftest s (d/create-database {}))")
        declared (law-ns old "(deftest ^{:seon.test/estimate {:ticks 1000} :seon.test/root []} t (ts/start-root! {}))"
                         "(deftest ^{:seon.test/estimate {:ticks 999999999999}} over (is true))")
        lacks (fn [db] (set (for [{:keys [test lack]} (problems/undeclared-tests db {} '#{app.law-test/old app.law-test/t app.law-test/h app.law-test/s app.law-test/over})]
                              [(symbol (name test)) lack])))
        caused (fn [head] (filterv #(re-find #"app\.law-test" %) (:caused (problems/caused-split base head))))]
    (is (= '#{[t :root] [h :root] [s :store]} (lacks undeclared))
        "each lack its row, the helper's root reached through the namespace's own code")
    (is (= '#{[over :ticks]} (lacks declared)) "declared: only the estimate over the ticks cap")
    (is (= 6 (count (caused undeclared))) "caused, refusing done: those three and the three changed tests' :unestimated-test, never the unchanged old's")
    (is (= 1 (count (caused declared))))))

;; docs/health.md#two-kinds-of-problem: a branch is judged on the content it makes, every class of it (the owner, 2026-11-12:
;; nothing switched off). For each content class a branch can make, the rows caused-split answers from what the branch moved are
;; the rows health reads in full on head and not on base
(deftest ^{:seon.test/estimate {:ticks 15000000}} a-branch-is-caused-each-content-class-it-makes
  (let [made (fn [base head c]
               (let [msgs (fn [db] (into #{} (map :message) (get-in (problems/health db) [:classes c :rows])))]
                 [(into #{} (comp (filter #(= c (:class %))) (map :message)) (:rows (problems/caused-split base head)))
                  (into #{} (remove (msgs base)) (msgs head))]))
        db0 (world)
        mapped (d/db-with db0 (seon.doc/load-tx db0 "docs" {"docs/README.md" "# Map\n\nThe map.\n\n- [a](a.md)\n" "docs/a.md" "# A\n\nThe page A.\n"}))
        paged (d/db-with mapped (seon.doc/load-tx mapped "docs" {"docs/b.md" "# B\n\nThe page B.\n\n## Listed\n\n- one\n"}))
        tasked (d/db-with db0 (seon.task/create-tx db0 [{:seon.task/ns 'app :seon.task/name "tile" :seon.task/kind :whole :seon.task/proofs #{'app-test/real}
                                                        :seon.task/plan "The root tile shows what boot loaded."}]))
        v 'app.calc/inc2
        planned (d/db-with db0 (seon.task/create-tx db0 [{:seon.task/ns 'app :seon.task/name "plan" :seon.task/kind :whole :seon.task/proofs #{'app-test/real}
                                                         :seon.task/plan (str "It changes `" v "`.")}]))
        moved (d/db-with planned [{:var/name v :var/form -1} {:db/id -1 :form/id "f-inc2-new" :form/src "(defn inc2 [x] (+ x 2))"}])]
    (doseq [[c base head] [[:leadless-section mapped paged] [:orphan-page mapped paged] [:uncited-task db0 tasked] [:stale-plan planned moved]]
            :let [[caused full] (made base head c)]]
      (is (seq full) (str (name c) ": the branch made a row"))
      (is (= full caused) (str (name c) ": caused-split answers it, as the full reading does")))
    (is (= (set (keys {:unnamed-site 1 :stale-section 1 :uncited-var 1 :long-form 1 :unfocused-docstring 1 :leadless-section 1 :orphan-page 1 :waived-proof 1 :uncited-task 1 :stale-plan 1 :unestimated-test 1 :undeclared-test 1 :unschemed-key 1 :refused-value 1}))
           seon.problems/caused-classes)
        "the classes a branch is judged on are the content classes, as :seon.problems/class declares them")))

(deftest ^{:seon.test/estimate {:ticks 8000000}} a-key-with-no-schema-entry-is-a-problem-and-a-branch-that-names-one-is-caused-it
  ;; docs/health.md#a-key-with-no-schema (the owner, 2026-11-12: "proper schemas for all fully namespaced keywords")
  (let [db0 (world)
        src-ns [{:db/id "ns" :ns/name 'seon.zz :ns/file "src/seon/zz.clj"} {:db/id "tns" :ns/name 'seon.zz-test :ns/file "test/seon/zz_test.clj"}]
        base (d/db-with db0 src-ns)
        form (fn [id ns ks] [{:db/id id :form/id (str "f-" id) :form/src "(def k :seon.zz/new-key)" :form/ns [:ns/name ns] :form/keywords ks}])
        head (d/db-with base (concat (form "a" 'seon.zz #{:seon.zz/new-key :seon.zz/_reverse :seon.host/fn}) (form "t" 'seon.zz-test #{:seon.zz/only-in-a-test})))
        keys-of (fn [db] (set (map :key (problems/unschemed-keys db {}))))
        entry [{:schema/key :seon.zz/new-key :schema/form "[:int {:description \"A key.\"}]"}]]
    (is (contains? (keys-of head) :seon.zz/new-key) "a key a source form names with no entry is a row")
    (is (not (contains? (keys-of head) :seon.zz/_reverse)) "a reverse ref is no key")
    (is (not (contains? (keys-of head) :seon.host/fn)) "a host type is its own entry (seon.contract/host-schemas)")
    (is (not (contains? (keys-of head) :seon.zz/only-in-a-test)) "a test's keyword is not src's")
    (is (not (contains? (keys-of (d/db-with head entry)) :seon.zz/new-key)) "its entry closes it")
    (is (some #(str/includes? % ":seon.zz/new-key") (problems/caused base head)) "a branch that names a new key with no entry is caused it")
    (is (not-any? #(str/includes? % ":seon.zz/new-key") (problems/caused base (d/db-with head entry))) "and one that writes the entry too is not")))

(deftest ^{:seon.test/estimate {:ticks 8000000}} a-branch-that-tightens-an-entry-is-caused-the-stored-values-it-refuses
  ;; docs/health.md#a-stored-value-its-entry-refuses: a value written before its attribute's entry tightened is held to the new entry
  ;; when a branch changes that entry: the store's datoms of that attribute alone, each refused one a row
  (let [base (d/db-with (world) [{:seon.agent/key "old" :seon.agent/boot "b" :seon.agent/started-ms 5}])
        entry (fn [form] [{:schema/key :seon.agent/started-ms :schema/form form}])
        caused-of (fn [head] (filter #(str/includes? % ":seon.agent/started-ms") (problems/caused base head)))]
    (is (seq (caused-of (d/db-with base (entry "[:int {:min 10 :description \"Ms.\"}]")))) "an entry tightened under a stored value is caused it")
    (is (empty? (caused-of (d/db-with base (entry "[:int {:min 0 :description \"Ms.\"}]")))) "one every stored value still fits is not")))

(deftest ^{:seon.test/estimate {:ticks 8000000}} a-branch-that-changes-what-its-plan-cites-is-shown-the-stale-plan-never-refused
  ;; docs/health.md#a-plan-that-went-stale: changing the code a plan cites is the work (an agent's own task cites what it
  ;; changes), so a stale plan is attention, as a stale section is; the e2e loop and swarm held every task at its cap when it refused
  (let [db0 (world)
        v 'app.calc/inc2
        planned (d/db-with db0 (seon.task/create-tx db0 [{:seon.task/ns 'app :seon.task/name "plan" :seon.task/kind :whole :seon.task/proofs #{'app-test/real}
                                                         :seon.task/plan (str "It changes `" v "`.")}]))
        moved (d/db-with planned [{:var/name v :var/form -1} {:db/id -1 :form/id "f-inc2-new" :form/src "(defn inc2 [x] (+ x 2))"}])
        split (problems/caused-split planned moved)]
    (is (= #{} (problems/caused planned moved)) "nothing refuses the branch's done")
    (is (some #(str/starts-with? % "app/plan cites app.calc/inc2") (:attention split)) "the agent is shown the plan its change made stale")))

;; ---- a judged cost verdict reads as a :cost problem --------------------------------------

;; Replaces the problems check step's claim, proved by running a cost proof through an agent, that a judged cost
;; proof's verdict reads as a :cost row of health (docs/health.md): estimate one world (~10 M ticks, as
;; seeded's) and two health reads of a db of one task, ~40 M ticks.
(deftest ^{:seon.test/estimate {:ticks 60000000}} a-judged-cost-verdict-reads-as-a-cost-problem
  (let [rows [{:n 1 :calls 10} {:n 10 :calls 100} {:n 100 :calls 2000}]
        red {:ok? false :over [{:dim :calls :n 100 :ratio 2.0}] :steeper [] :under [] :unmeasured []}
        green {:ok? true :over [] :steeper [] :under [] :unmeasured []}
        db (d/db-with (world)
                      [(ended "t/slow" {:seon.task/proofs #{'app.t/f-cost 'app.t/g-cost}
                                        :seon.task/costs {'app.t/f-cost {:rows rows :verdict red}
                                                          'app.t/g-cost {:rows rows :verdict green}}})])
        cost (get-in (problems/health db) [:classes :cost :rows])
        row (first cost)]
    (is (= 1 (count cost)) "the red verdict is one row; the green one is none")
    (is (= ["t/slow" 'app.t/f-cost red rows] ((juxt :task :test :verdict :rows) row)) "its task, test, verdict and rows")
    (is (false? (get-in row [:verdict :ok?])))
    (is (empty? (get-in (problems/health (d/db-with (world) [(ended "t/ok" {:seon.task/proofs #{'app.t/g-cost}
                                                                            :seon.task/costs {'app.t/g-cost {:rows rows :verdict green}}})]))
                        [:classes :cost :rows])) "a green verdict reads as no problem")))
