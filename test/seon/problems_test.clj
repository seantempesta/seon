(ns seon.problems-test
  "Session D2: every class of problem arrives as a typed fact written by its observer, and
  seon.problems/report lists them by class at a cost that follows the problems, not the
  program. Run: bin/test seon.problems-test"
  (:require [seon.test-store :as ts] [seon.profile :as profile] [clojure.test :refer [deftest is testing]] [clojure.edn :as edn]
            [datahike.api :as d] [clojure.core.async :as a]
            [seon.contract :as contract] [seon.db :as pdb] [seon.error :as err] [seon.agent :as agent]
            [seon.repl :as repl] [seon.proofs :as proofs] [seon.root :as root] [seon.agent-proc :as ap]
            [seon.reconcile :as rec] [seon.sock :as sock]
            [seon.render :as render] [konserve.jolt.fasl] [clojure.set] [clojure.string :as str] [seon.source] [seon.fx :as fx] [seon.model :as model] [clojure.data.json] [seon.problems :as problems] [seon.task] [seon.template] [seon.cost :as cost] [seon.docs]))

(defn- invalid [op v]
  (contract/error :seon.instrument/contract-error op (str op " invalid-input: its arguments do not satisfy its contract")
                  {:seon.error/member :malli.core/invalid-input
                   :seon.error/problems [{:path [0] :in [0] :expected :int :value {:seon.print/code (pr-str v)}}]}))

;; ---- the signature and the fold (ported from R4's seon.error-test) ----------------------

(deftest one-cause-one-signature
  (is (= (err/signature (invalid 'app/f nil)) (err/signature (invalid 'app/f 42))) "values never enter it")
  (is (not= (err/signature (invalid 'app/f nil)) (err/signature (invalid 'app/g nil))) "another producer, another signature")
  (is (= (err/signature {:seon.error/type :x/y :seon.error/message "slot 17 of 64"})
         (err/signature {:seon.error/type :x/y :seon.error/message "slot 3 of 64"})) "digits are normalised")
  (is (not= (err/signature {:seon.error/type :seon.agent/died :seon.error/message "died" :exit {:signal 9}})
            (err/signature {:seon.error/type :seon.agent/died :seon.error/message "died" :exit {:signal 11}}))
      "an exit status names a different cause"))

(defn- occ [e agent ms] (err/occurrence e :eval {:agent agent :task "t/a" :form "(f 1)"} ms))

(deftest a-storm-is-one-write-per-window
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

(deftest a-written-signature-is-not-written-again
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

(deftest an-eval-error-is-recorded-as-data
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
          r (repl/response [{"status" ["eval-error"] :tag :err :err em :id 0 :form "(/ 1 0)"}] 'result/e1)
          rec (some :seon.eval/record (repl/record-tx {:seon.agent/key "b/a1" :seon.agent/id :a1} "e1" "a1"
                                                      {:code "(/ 1 0)" :response r}))]
      (is (= em (:ex r)))
      (is (= em (get-in rec [:response :ex])) "grouped by type and producer without parsing text")))
  (testing "the agent folds its errors until its commit; root folds them as :eval occurrences"
    (let [em (agent/error-map (ArithmeticException. "Divide by zero"))
          m (-> nil (err/note em :eval "(/ 1 0)" 10) (err/note em :eval "(/ 2 0)" 12))
          [s] (vals m)
          o (err/sent->occurrence s :a4 "t/x")]
      (is (= [2 10 12 "(/ 1 0)"] [(:n s) (:first-ms s) (:last-ms s) (:form s)]))
      (is (= {:count 2 :agents #{:a4} :class :eval} (select-keys (err/fold-entry nil o) [:count :agents :class]))))))

;; ---- red proofs: the failing assertions and cost verdicts, persisted ---------------------

(deftest a-red-assertion-is-persisted
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
    (is (contract/valid? :seon.run/failures fs))
    (is (= "(= 3 0)" (:expected (first (get fs 'app.t/red)))) "printed by the one printer")
    (is (= {'app.t/f-cost {:rows [] :verdict verdict}} cs) "a cost proof's rows and verdict, as data")))

;; ---- root's observations: deaths, stalls, platform errors, effect errors ----------------

(defn- api [& [view]]
  (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 64)
                    :view (merge {:agents {}} view) :graph (promise)
                    :system (doto (promise) (deliver {:procs {} :conns [] :parts {[:proc :api] [{:proc 'seon.root/api-proc} nil]}}))}))

(defn- step [st in m] (#'root/api-proc st in m))

(defn- writes [outs] (vec (keep #(when (= :tx (:op %)) (:tx-data %)) (:to-writer outs))))

(deftest a-platform-error-is-written-once
  (let [msg {:op :error :pid :api :message "class java.lang.String cannot be cast to java.lang.Number (17)" :data nil}
        [st o1] (step (api) :back msg)
        [w] (writes o1)
        rid (get-in (first (:to-writer o1)) [:dest])
        [st outs] (reduce (fn [[st acc] _] (let [[st o] (step st :back msg)] [st (into acc (writes o))])) [st []] (range 500))
        _ (is (= [] outs) "one write in flight at a time: the storm only folds")
        [_ o3] (step st :from-writer {:dest rid :r {:ok true :t 9}})]
    (is (= 1 (:seon.error/count (first w))))
    (is (= :platform (:seon.error/class (first w))))
    (is (= 'seon.root/api-proc (:seon.error/operation (first w))) "the failed proc is the producer")
    (is (= [] (writes o3)) "the answered write is followed by nothing: 500 repeats of a written signature are counted in the fold")))

(deftest an-agent-death-is-recorded-with-its-cause
  (let [dir (str (System/getProperty "java.io.tmpdir") "/d2-death-" (profile/now-ns))
        _ (.mkdirs (java.io.File. (str dir "-agents")))
        _ (spit (str dir "-agents/a1.err") (str (apply str (repeat 3000 "x")) "\nChez: out of memory\n"))
        p (promise)
        st (-> (api {:store {:path dir :agents-dir (str dir "-agents")} :agents {:a1 {:status :busy :pid 77 :task "t/x" :running {:form "(work!)" :since-ms 5}}}})
               (assoc-in [:agents :a1] {:branch :b1}) (assoc-in [:pending 3] {:p p :kind :eval :agent :a1}))
        ;; the api's end of an agent (its graph's update! is the api's, not this test's)
        end (fn [st m] (let [o (volatile! {}) st (#'root/end-agent st o m)] [(dissoc (#'root/flush-problems st o) :seon.root/update) @o]))
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

(deftest root-records-what-went-wrong-in-an-agent
  ;; the one producer of what is wrong (root's problems tile, seon.render.ui/problems-tile, reads only what is recorded):
  ;; root records a red run of an agent with no task, and a refused spawn
  (let [view {:agents {:a1 {:status :idle} :a2 {:status :idle :task "t/x"}}}
        red {:pass 1 :fail 1 :error 0 :reports [{:type :pass :var "#'app.t/ok"} {:type :fail :var "#'app.t/red"}]}
        written (fn [[_ o]] (mapv (juxt :seon.error/class :seon.error/type :seon.error/message :seon.error/last-agent) (first (writes o))))]
    (testing "a red run of an agent with no task"
      (is (= [[:agent :seon.agent/failed "tests red: #'app.t/red" :a1]]
             (written (step (api view) :back {:op :status :agent :a1 :set {:tests red}})))))
    (testing "a task's agent: its run records its reds at its end, not root"
      (is (= [] (written (step (api view) :back {:op :status :agent :a2 :set {:tests red}})))))
    (testing "a refused spawn, of any agent"
      (is (= [[:agent :seon.agent/failed "fork server went down before the fork" :a2]]
             (written (step (api view) :back {:op :status :agent :a2
                                              :set {:error (contract/error :seon.agent/failed 'seon.root/spawn! "fork server went down before the fork" {:agent :a2})}})))))))

(deftest a-stall-is-observed-by-the-agent-proc
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

(deftest a-stall-is-a-fact-on-the-agent-until-it-speaks-or-ends
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

(deftest a-stall-in-a-live-run-makes-one-task-for-root
  ;; owner, 2026-10-26: no push; a stall "becomes a task for root to deal with, from a template":
  ;; the first write of a stall in a live run writes one task (seon.template/stall), its cause
  ;; pre-filled with the stall, its proofs the boundary conditions; a later stall of the same run
  ;; writes none (one per agent and run), and an agent working no run gets none
  (let [db (ts/test-db)
        view {:agents {:a1 {:status :busy :pid 77 :task "demo/t" :run "demo/t#1"} :a2 {:status :busy :pid 78}}}
        stalled (fn [ag] {:op :stalled :agent ag
                          :error (contract/error :seon.agent/stalled 'seon.agent-proc/agent-in (str "agent " (name ag) " sent nothing for 4000 ms with a request in flight")
                                                 {:agent ag :quiet-ms 4000 :last-op {:kind :tests :form "#'app.t/hangs"}})})
        tasks (fn [o] (filter :seon.task/id (into [] cat (writes o))))
        [_ o1] (step (assoc (api view) :conn (atom db)) :back (stalled :a1))
        [t] (tasks o1)]
    (is (= ["root/stall-a1-demo-t-1"] (mapv :seon.task/id (tasks o1))) "one task, named for the agent and its run")
    (is (= {:agent :a1 :run "demo/t#1" :quiet-ms 4000 :pid 77 :last-op {:kind :tests :form "#'app.t/hangs"}} (dissoc (get-in t [:seon.task/cause :stall]) :api))
        "its cause pre-filled with the stall")
    (is (= {:open [] :prove nil} (get-in t [:seon.task/cause :stall :api]))
        "and what the api held then: no ask open, no report from the test service yet")
    (is (= [:held "outside" seon.template/stall-proofs] [(:seon.task/execution t) (:seon.task/model t) (set (:seon.task/proofs t))])
        "held, for an outside worker, its proofs the boundary conditions")
    (is (empty? (tasks (second (step (assoc (api view) :conn (atom (d/db-with db (vec (tasks o1))))) :back (stalled :a1)))))
        "a later stall of the same run writes none")
    (is (empty? (tasks (second (step (assoc (api view) :conn (atom db)) :back (stalled :a2))))) "an agent working no run: none")))

(deftest a-stall-tasks-evidence-is-captured-while-it-is-stuck
  ;; the evening audit (a6's hang left no evidence: the root restarted and the process went with
  ;; it): the stall task's first write asks for the stuck process's evidence (the :evidence effect,
  ;; seon.fx/stall-evidence), and its answer's files are written into the task's cause on main
  (let [db (ts/test-db)
        view {:agents {:a1 {:status :busy :pid 77 :task "demo/t" :run "demo/t#1"}}}
        st (assoc (api view) :conn (atom db) :system {:procs {:fx/evidence {}}})
        stalled {:op :stalled :agent :a1
                 :error (contract/error :seon.agent/stalled 'seon.agent-proc/agent-in "agent a1 sent nothing for 4000 ms with a request in flight"
                                        {:agent :a1 :quiet-ms 4000})}
        [st o1] (step st :back stalled)
        [ask] (get o1 [:fx/evidence :in])
        db (d/db-with db (vec (filter :seon.task/id (into [] cat (writes o1)))))
        files {:sample "/x/sample.txt" :lsof "/x/lsof.txt"}
        [_ o2] (step (assoc st :conn (atom db)) :back {:dest (:dest ask) :r {:value {:dir "/x" :files files}}})
        [[_ _ attr cause]] (filter #(and (vector? %) (= :seon.task/cause (nth % 2 nil))) (into [] cat (writes o2)))]
    (is (= 77 (get-in ask [:req :pid])) "asked for the stuck process")
    (is (str/ends-with? (get-in ask [:req :dir]) "/stalls/stall-a1-demo-t-1") "its files under the runs directory's stalls/, by the task")
    (is (= :seon.task/cause attr))
    (is (= files (:evidence cause)) "the evidence's files on the task's cause")
    (is (= :a1 (get-in cause [:stall :agent])) "beside the stall it was captured for")))

(deftest a-stalled-agent-is-an-open-problem-while-the-fact-holds
  (let [db (d/db-with (ts/test-db) [{:seon.agent/key "b1/a1" :seon.agent/id :a1}
                                    {:seon.process/id "b1/a1/77" :seon.process/agent [:seon.agent/key "b1/a1"] :seon.process/pid 77 :seon.process/forked-ms 10.0
                                     :seon.process/stall {:quiet-ms 4000 :last-op {:kind :tests :form "#'app.t/hangs"}}}
                                    {:seon.agent/key "b1/a2" :seon.agent/id :a2}])
        rows (get-in (problems/health db) [:classes :stalled :rows])]
    (is (= [[:a1 77 "#'app.t/hangs"]] (mapv (juxt :agent (comp :pid :evidence) (comp :form :waits-on :evidence)) rows)) "one row, the stalled agent's")
    (is (re-find #"a1 sent nothing for 4000 ms.*tests #'app.t/hangs" (:message (first rows))) "saying what it waits on")
    (is (empty? (get-in (problems/health (d/db-with db [[:db.fn/retractAttribute [:seon.process/id "b1/a1/77"] :seon.process/stall]])) [:classes :stalled :rows]))
        "closed once the fact is retracted")
    (is (empty? (get-in (problems/health (d/db-with db (seon.task/earlier-agents-tx db))) [:classes :stalled :rows]))
        "and by a new boot: an earlier boot's stall ended with its process")
    ;; a stall left on an earlier process of the agent is no row: only its current process's counts
    (is (empty? (get-in (problems/health (d/db-with db [{:seon.process/id "b1/a1/78" :seon.process/agent [:seon.agent/key "b1/a1"] :seon.process/pid 78 :seon.process/forked-ms 20.0}]))
                        [:classes :stalled :rows]))
        "a respawned agent's old process's stall is no row")))

(defn- gone-ended? [r] (= :seon.root/ended (:seon.error/type r)))

(deftest a-late-live-status-leaves-a-gone-agent-gone
  ;; task seon.root/a-gone-agent-stays-gone-and-every-request-is-answered, path A
  (testing "A: a late live status leaves a gone agent gone, out of the stall check's set"
    (let [[st _] (step (api {:agents {:a1 {:status :dead :pid 77 :ended-ms 5}}}) :back {:op :status :agent :a1 :set {:status :busy :running {:form "(work!)" :since-ms 6}}})]
      (is (= [:dead nil] ((juxt :status :running) (get-in st [:view :agents :a1]))))
      (is (not (contains? (:seon.root/busy st) :a1))))))

(deftest an-ending-agents-every-request-is-answered
  ;; task seon.root/a-gone-agent-stays-gone-and-every-request-is-answered, path A
  (testing "A: an ending agent's every request the api holds is answered, one that never reached its proc too; a prove is the test service's"
    (let [p (promise) q (promise)
          st (-> (api {:agents {:a1 {:status :killed :pid 77}}}) (assoc-in [:agents :a1] {:branch :b1})
                 (assoc-in [:pending 5] {:p p :kind :eval :agent :a1}) (assoc-in [:pending 6] {:p q :kind :prove :agent :a1}))
          o (volatile! {})
          st' (#'root/end-agent st o {:op :ended :agent :a1 :rid nil :pid 77 :inflight []})]
      (is (gone-ended? (deref p 0 nil)) "the eval sent as it ended: answered")
      (is (= ::unanswered (deref q 0 ::unanswered)) "the prove: left to the service that answers it")
      (is (contains? (:pending st') 6)))))

(deftest a-stopped-agents-exit-is-recorded-on-it
  ;; task seon.root/a-gone-agent-stays-gone-and-every-request-is-answered, path B
  (testing "B: a stopped agent's exit, which no death waits on, is still recorded on it"
    (let [[st _] (step (api {:agents {:a0 {:status :dead :pid 77 :ended-ms 1 :exit {:exit 0}} :a1 {:status :killed :pid 77 :ended-ms 9}}})
                       :from-fs {:ev :exit :pid 77 :status {:signal 9}})]
      (is (= [{:exit 0} {:signal 9}] (mapv #(get-in st [:view :agents % :exit]) [:a0 :a1]))))))

(deftest an-exit-goes-to-the-death-waiting-on-its-pid
  ;; task seon.root/a-gone-agent-stays-gone-and-every-request-is-answered, path B
  (testing "B: an exit goes to the death waiting on its pid, never to an ended agent's entry with the same pid"
    (let [died (contract/error :seon.agent/died 'seon.root/end-agent "agent a1 died" {:agent :a1 :pid 77})
          st (-> (api {:agents {:a0 {:status :dead :pid 77 :ended-ms 1} :a1 {:status :dead :pid 77 :ended-ms 9}}})
                 (assoc :seon.root/dying {77 [:a1 died []]}))
          [st _] (step st :from-fs {:ev :exit :pid 77 :status {:signal 9}})]
      (is (= [nil {:signal 9}] (mapv #(get-in st [:view :agents % :exit]) [:a0 :a1])))
      (is (empty? (:seon.root/dying st)) "the death recorded"))))

(deftest the-hello-answers-the-spawn-whatever-its-status
  ;; task seon.root/a-gone-agent-stays-gone-and-every-request-is-answered, path D
  (testing "D: the hello answers the spawn though an eval sent before it made the agent busy"
    (let [p (promise)
          st (-> (api {:agents {:a1 {:status :busy :pid 77}}}) (assoc-in [:seon.root/spawning :a1] p))
          _ (step st :back {:op :status :agent :a1 :set {:ready true}})]
      (is (= {:id :a1} (deref p 0 nil))))))

(deftest a-new-boot-ends-an-earlier-boots-live-agents
  ;; task seon.root/a-gone-agent-stays-gone-and-every-request-is-answered, path C
  (testing "C: a new boot writes :dead for an earlier boot's agent still shown live"
    (let [db (d/db-with (ts/test-db) [{:db/id "r" :seon.root/id :root} {:seon.agent/key "b0/a1" :seon.agent/id :a1 :seon.agent/root "r"
                                                            :seon.agent/status :busy}
                                                            {:seon.process/id "b0/a1/5" :seon.process/agent [:seon.agent/key "b0/a1"] :seon.process/running "(work!)"}])
          db (d/db-with db (seon.task/earlier-agents-tx db))]
      (is (= [:dead nil] [(:seon.agent/status (d/entity db [:seon.agent/key "b0/a1"])) (:seon.process/running (d/entity db [:seon.process/id "b0/a1/5"]))])))))

(deftest an-effect-error-is-counted-by-root
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
(deftest an-askers-failed-effect-is-one-row-closed-by-its-next-good-outcome
  (let [co (doto (java.io.File. (str (System/getProperty "java.io.tmpdir") "/seon-unreadable-" (random-uuid)) "src") (.mkdirs))
        _ (doto (java.io.File. co "a.clj") (spit "(ns a)") (.setReadable false))
        ;; the source's read failed, as its owner answers it: stamped with when (seon.fx/worker-proc)
        failed (assoc (fx/read-source {:req {:root (.getParent co) :dirs ["src"]}}) :seon.fx/at-ms 1000)
        rows #(get-in (problems/report %) [:classes :effect :rows])
        open? #(not (false? (:open? (first (rows %)))))
        import (fn [db r fact] (d/db-with db (err/asked-tx db r :seon.root/source [{:seon.root/id :root :seon.root/source fact}])))
        bad {:root "/c" :files 0 :forms 0 :ms 1.0 :error (:seon.error/message failed)}
        good {:root "/c" :files 1 :forms 1 :ms 2.0 :digest "d"}]
    (is (= :seon.fx/io-error (:seon.error/type failed)) "an unreadable file fails the read")
    (testing "a failed import, then a good one"
      (let [db (import (ts/test-db) failed bad)]
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
            db0 (ts/test-db)
            db (d/db-with db0 (err/asked-tx db0 git :seon.run/commit [{:seon.run/id "r1" :seon.run/commit-error (:seon.error/message git)}]))
            fixed (d/db-with db (err/asked-tx db {:value {:sha "abc"}} :seon.run/commit [{:seon.run/id "r2" :seon.run/commit "abc"}]))]
        (is (= 1 (count (rows db))))
        (is (open? db) "a failed commit writes no sha: open")
        (is (false? (:open? (first (rows fixed)))) "the next merge's commit writes one: closed")))
    (testing "an error the asker made itself (no owner's stamp) writes its fact alone"
      (is (= [{:a 1}] (err/asked-tx (ts/test-db) (contract/error :seon.fx/io-error 'my.root/gate! "no gate checkout" {}) :seon.run/deferred-results [{:a 1}]))))
    (.setReadable (java.io.File. co "a.clj") true)
    (run! #(.delete ^java.io.File %) (reverse (file-seq (.getParentFile co))))))

;; ---- the read -----------------------------------------------------------------------------

(defn- seeded
  "A main db with n recorded errors, n red runs and n dead runs over a task each,
  plus k clean tasks with green runs (the program the read must not cost)."
  [n k]
  (ts/test-branch! (fn [_ conn] (let [f (reduce err/fold {} (for [i (range n)] (err/occurrence {:seon.error/type :seon.agent/eval-error :seon.error/operation 'seon.repl/eval-code
                                                                  :seon.error/message (str "Unable to resolve symbol: s" (char (+ 97 (mod i 26))) i) :class "x"}
                                                                 :eval {:agent :a1 :task "t/x0"} 100)))
        [_ tx] (err/flush-tx f @conn)
        att (fn [tid aid m] (merge {:seon.run/id aid :seon.run/task [:seon.task/id tid] :seon.run/boot "b" :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/id :a1}
                                    :seon.run/start-ms 1 :seon.run/end-ms 2 :seon.run/branch :b :seon.run/max-turns 2} m))
        task (fn [tid] {:seon.task/id tid :seon.task/ns 't :seon.task/name (subs tid 2) :seon.task/plan "p" :seon.task/execution :running
                        :seon.task/branch :b :seon.task/kind :work})]
    (d/transact conn tx)
    (d/transact conn (vec (concat (for [i (range k)] (task (str "t/c" i)))
                                  (for [i (range k)] (att (str "t/c" i) (str "t/c" i "#1") {:seon.run/status :done :seon.run/outcome :green}))
                                  (for [i (range n)] (assoc (task (str "t/r" i)) :seon.task/proofs #{'app.t/red}))
                                  (for [i (range n)] (att (str "t/r" i) (str "t/r" i "#1")
                                                          {:seon.run/status :closed :seon.run/outcome :cap
                                                           :seon.run/failures {'app.t/red [{:type :fail :message "m" :expected "1" :actual "2"}]}}))
                                  (for [i (range n)] (task (str "t/d" i)))
                                  (for [i (range n)] (att (str "t/d" i) (str "t/d" i "#1")
                                                          {:seon.run/status :dead :seon.run/reason "its agent died"
                                                           :seon.run/error (contract/error :seon.agent/died 'seon.root/end-agent "agent a1 died" {:agent :a1})})))))
    @conn))))

(deftest a-cancelled-tasks-run-problems-close
  (let [db (seeded 2 1)
        red (fn [db] (get-in (problems/report db) [:classes :red :rows]))
        dead (fn [db] (get-in (problems/report db) [:classes :dead :rows]))]
    (is (every? :open? (red db)) "a red run of a task still to be worked is open")
    (is (every? :open? (dead db)))
    (let [cancelled (d/db-with db [{:seon.task/id "t/r0" :seon.task/execution :cancelled}
                                   {:seon.task/id "t/d0" :seon.task/execution :cancelled}])
          open (fn [rows task] (:open? (first (filter #(= task (:task %)) rows))))]
      (is (false? (open (red cancelled) "t/r0")) "the cancelled task's red run closes")
      (is (false? (open (dead cancelled) "t/d0")) "and its dead run")
      (is (true? (open (red cancelled) "t/r1")) "another task's stays open"))))

(defn- measured-health [db]
  (let [m (volatile! nil) h (profile/measure! #(problems/health db) #(vreset! m %))]
    [h @m]))

(deftest health-reads-each-part-once
  (let [db0 (seeded 3 2)
        db (d/db-with db0 (seon.docs/load-tx db0 "demo" {"demo/p.md" "# P\n\nIt says what it is about.\n"}))
        [h m] (measured-health db)
        rows (get-in h [:classes :problem-unlinked :rows])]
    (is (seq rows) "the db has unlinked classes to judge")
    (is (= rows (vec (distinct rows))) "no row twice")
    (is (= :problem-unlinked (:class (peek rows))) "the unlinked class's own missing section is the last row")
    (is (<= (:calls m) 63) "the contracted calls of one health read (59 before the fix; 51 with it and report-with's own contract; main has since added to health's reads, health-with's contract is one more, and the old-word class reads its table and texts in 4 contracted calls)")))

(deftest problems-lists-every-class-with-what-a-declaration-needs
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
      (is (= {:from (:run red) :red ['app.t/red]} (select-keys (:evidence red) [:from :red])) "evidence as data")
      (is (= 't (:owner-ns red)))
      (is (:open? red)))
    (let [e (first (get-in r [:classes :eval :rows]))]
      (is (= 'seon.repl/eval-code (:producer e)))
      (is (= :error (get-in e [:evidence :kind])))
      (is (= [] (:answered-by e)))
      (is (some? s)))
    (testing "a merge: its conflicts, unmerged keys and the writer's refused answer, as evidence"
      (let [behind (contract/error :seon.merge/behind 'seon.writer/merge-refused "main changed 2 key(s)" {:behind 2})
            db2 (:db-after (d/with db [{:seon.task/id "t/m" :seon.task/ns 't :seon.task/name "m" :seon.task/plan "p" :seon.task/execution :running
                                         :seon.task/branch :b :seon.task/kind :merge}
                                        {:seon.run/id "t/m#1" :seon.run/task [:seon.task/id "t/m"] :seon.run/boot "b" :seon.run/agent {:seon.agent/key "b/a2" :seon.agent/id :a2}
                                         :seon.run/start-ms 1 :seon.run/end-ms 9 :seon.run/branch :b :seon.run/max-turns 2
                                         :seon.run/status :behind :seon.run/unmerged ["[:var app/g]"]
                                         :seon.run/conflicts [{:seon.merge/key [:var 'app/f] :seon.merge/kind :both-modified}]
                                         :seon.run/error behind}]))
            [m] (get-in (problems/report db2) [:classes :merge :rows])]
        (is (= ["t/m#1" :behind 1 ["[:var app/g]"] 'seon.writer/merge-refused]
               [(:run m) (:status m) (:conflicts m) (:unmerged m) (:producer m)]))
        (is (= [{:key [:var 'app/f] :kind :both-modified}] (get-in m [:evidence :conflicts])))
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
  [n] [(seeded n 20)])

(deftest problems-reads-only-the-problems
  (cost/proof {:of 'seon.problems/report :input 'seon.problems-test/rows-input
               :n "recorded problems of each class (per n: one signature, one red run, one dead run)"
               :work (str "one index seek per class, then per row one pull: the signature's 13 attributes; a run's 12 and "
                          "its task's 2 (a nested pull), and one status pull to know whether its task is done; about 500 "
                          "characters of EDN read (example, failures, error)")
               :growth :linear
               :ops {:db/pull 7 :db/pull-attr 38 :edn/read-char 500}
               :sizes [4 20 100]
               ;; measured on Jolt 00a3b321 (pulls got cheaper with J3's try; the read did not as much)
               :overrun {:task "seon.problems/report-beyond-its-pulls" :measured {:ns 218794000.0 :bytes 227801600.0}}}))

(defn clean-input
  "The flatness proof's input at k: 4 problems of each class over k clean tasks, each with a
  green run."
  [k] [(seeded 4 k)])

(defn- diff [base head] (apply concat (d/datoms-diff base head :eavt [])))

(defn history-input
  "The flatness proof's input at k: the health of main with 4 problems of each class beside k finished
  tasks, each with a merged run as production writes it (judge-tx records its cost verdict and its
  waste too), then main one run later: [health base head moved]."
  [k] (let [base (ts/test-branch! (fn [_ conn]
                                    (d/transact conn (vec (concat
                                                           (for [i (range k)] {:seon.task/id (str "t/h" i) :seon.task/ns 't :seon.task/name (str "h" i) :seon.task/plan "p"
                                                                               :seon.task/execution :running :seon.task/branch :b :seon.task/kind :work})
                                                           (for [i (range k)] {:seon.run/id (str "t/h" i "#1") :seon.run/task [:seon.task/id (str "t/h" i)] :seon.run/boot "b"
                                                                               :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/id :a1} :seon.run/start-ms 1 :seon.run/end-ms 2
                                                                               :seon.run/branch :b :seon.run/max-turns 2 :seon.run/status :merged :seon.run/outcome :green
                                                                               :seon.run/costs {'app.t/cheap {:verdict {:ok? true :under []} :rows []}}
                                                                               :seon.run/waste {:turns 1}}))))
                                    @conn))
            head (d/db-with base [{:seon.run/id "t/h0#2" :seon.run/task [:seon.task/id "t/h0"] :seon.run/boot "b"
                                   :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/id :a1} :seon.run/start-ms 3 :seon.run/end-ms 4
                                   :seon.run/branch :b :seon.run/max-turns 2 :seon.run/status :merged :seon.run/outcome :green}])]
        [(problems/health base) base head (diff base head)]))

(defn own-input
  "The proof's input at k: a db with k finished tasks beside the run of one: [db run-entity]."
  [k] (let [[_ base] (history-input k)] [base (pdb/eid base :seon.run/id "t/h0#1")]))

(deftest an-agents-own-problems-cost-nothing-for-the-history
  (cost/proof {:of 'seon.problems/own :input 'seon.problems-test/own-input
               :n "finished tasks, each with a merged run, beside the run asked for"
               :work "the run's pull, its task's, and the error signatures' last-task datoms: none per finished task"
               :growth :constant
               :ops {:db/pull 0 :db/pull-attr 0 :edn/read-char 0}
               :fixed {:ticks 85000 :bytes 320000 :calls 4}
               :sizes [10 100 1000]}))

(deftest problems-move-at-the-cost-of-what-changed
  ;; main moves by one run while its finished history grows: the health of the move reads the run it
  ;; touched, not the history (health-moved; report read every run's cost verdict and waste)
  (cost/proof {:of 'seon.problems/health-moved :input 'seon.problems-test/history-input
               :n "finished tasks, each with a merged run carrying a cost verdict and waste"
               :work "no pull and no contracted call per finished task: the move's datoms (given, the render proc's) name the touched run and its task's runs, the derived classes are carried and read where the two dbs differ; the program's changes come from the moved datoms (seon.merge/delta-changes given them), and the per-task part is the cold index nodes the task scans touch"
               :growth :linear
               :per-n {:ticks 1700 :bytes 10500 :calls 0}
               :fixed {:ticks 650000 :bytes 3000000 :calls 54}
               :sizes [10 100 1000]}))

(deftest problems-cost-nothing-for-a-clean-program
  (cost/proof {:of 'seon.problems/report :input 'seon.problems-test/clean-input
               :n "clean tasks (each with a green run), beside 4 problems of each class"
               :work "none per clean task: the indexes the read seeks hold only problem rows; the 4 problem sets cost what they cost"
               :growth :constant
               :ops {:db/pull 28 :db/pull-attr 152 :edn/read-char 2000}
               :fixed {:bytes 750000}
               :sizes [10 100 1000]}))

;; ---- the model call: thinking mode as a provider field, its cost and its empty reply as data ----

(def ^:private deepseek {:kind :openai-compat :base-url "https://api.example" :model "deepseek-flash" :api-key-env "NONE" :max-tokens 2048})
(def ^:private req {:messages [{:role "user" :content "hi"}] :task "t/a" :turn 0})

(deftest the-request-sets-thinking-only-when-the-provider-does
  ;; tool_choice auto: the model may reply in prose alone, which ends the turn (design, owner 2026-10-24)
  (is (= {:model "deepseek-flash" :messages (:messages req) :tools [model/eval-tool] :tool_choice "auto" :max_tokens 2048 :temperature 0 :stream true :stream_options {:include_usage true}}
         (model/request-body deepseek req)) "thinking absent: the provider's default; max-tokens the provider's; the model chooses whether to call; streamed with its usage")
  (is (= {:type "disabled"} (:thinking (model/request-body (assoc deepseek :thinking :disabled) req))))
  (is (= ["high" 512] ((juxt :reasoning_effort :max_tokens) (model/request-body (assoc deepseek :reasoning-effort :high :max-tokens 512) req))))
  (is (contract/valid? :seon.model/provider (assoc deepseek :thinking :enabled :reasoning-effort :low))))

(deftest a-reply-carries-what-it-cost-and-an-eaten-reply-is-a-problem
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

(deftest a-reply-cut-at-max-tokens-is-marked-cut
  ;; T5's first real run: replies of exactly max-tokens whose code was cut, recorded nowhere
  (let [body (fn [finish] (clojure.data.json/write-str {:choices [{:message {:content "(defn f [x]" } :finish_reason finish}]
                                                        :usage {:prompt_tokens 10 :completion_tokens 4096}}))]
    (is (true? (:truncated? (model/parse-reply (body "length") 9.0 4096))))
    (is (nil? (:truncated? (model/parse-reply (body "stop") 9.0 4096))) "a reply that stopped on its own is not cut")))

(deftest another-tasks-unfinished-proof-is-not-a-problem
  ;; T5's real run: one run's run reports every program test, so 210 rows named other
  ;; tasks' proofs not done yet (allowed reds); only the task's own proofs are its problems
  (ts/test-branch! (fn [_ conn]
    (d/transact conn [{:seon.task/id "t/a" :seon.task/ns 't :seon.task/name "a" :seon.task/plan "p" :seon.task/execution :running
                       :seon.task/branch :b :seon.task/kind :work :seon.task/proofs #{'app.t/a-test}}])
    (d/transact conn [{:seon.run/id "t/a#1" :seon.run/task [:seon.task/id "t/a"] :seon.run/boot "b" :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/id :a1}
                       :seon.run/start-ms 1 :seon.run/end-ms 2 :seon.run/branch :b :seon.run/max-turns 2
                       :seon.run/status :ended :seon.run/outcome :red
                       :seon.run/failures {'app.t/a-test [{:type :fail :expected "1" :actual "2"}]
                                               'app.t/other-test [{:type :fail :expected "3" :actual "4"}]}}])
    (is (= ['app.t/a-test] (mapv :test (get-in (problems/report @conn) [:classes :red :rows])))))))

(deftest a-lost-run-is-a-death
  ;; task seon.render/one-owner-per-view-path (A1): the failed run statuses are the
  ;; declared set (:seon.status/failed), not a literal in the reader
  (let [db (:db-after (d/with (seeded 1 5)
                              [{:seon.task/id "t/l" :seon.task/ns 't :seon.task/name "l" :seon.task/plan "p" :seon.task/execution :running
                                :seon.task/branch :l :seon.task/kind :work}
                               {:seon.run/id "t/l#1" :seon.run/task [:seon.task/id "t/l"] :seon.run/boot "b" :seon.run/agent {:seon.agent/key "b/a9" :seon.agent/id :a9}
                                :seon.run/start-ms 1 :seon.run/end-ms 9 :seon.run/branch :l :seon.run/max-turns 2
                                :seon.run/status :lost}]))
        rows (get-in (problems/report db) [:classes :dead :rows])]
    (is (= :lost (:status (first (filter #(= "t/l#1" (:run %)) rows)))))
    (is (not-any? #(= "t/l#1" (:run %)) (get-in (problems/report db) [:classes :error :rows])))))

(deftest a-merge-with-its-process-tests-unrun-is-an-open-problem
  ;; owner's ruling, 2026-10-23: a change that reaches process tests merges with them unrun
  ;; unless root asked; unknown stays visible, an open :unrun row, until each is recorded
  ;; passing (my.root/gate! runs them): one passing of two, or a red, keeps it open
  (let [att (fn [extra] (:db-after (d/with (seeded 1 5)
                                           [{:seon.task/id "t/u" :seon.task/ns 't :seon.task/name "u" :seon.task/plan "p" :seon.task/execution :running
                                             :seon.task/branch :u :seon.task/kind :merge}
                                            (merge {:seon.run/id "t/u#1" :seon.run/task [:seon.task/id "t/u"] :seon.run/boot "b" :seon.run/agent {:seon.agent/key "b/a9" :seon.agent/id :a9}
                                                    :seon.run/start-ms 1 :seon.run/end-ms 9 :seon.run/branch :u :seon.run/max-turns 2
                                                    :seon.run/status :merged :seon.run/deferred ['seon.root-test/boots 'seon.check-test/clean]}
                                                   extra)])))
        rows #(get-in (problems/report %) [:classes :unrun :rows])]
    (is (= [["t/u#1" "merged with 2 process tests unrun" true]] (mapv (juxt :run :reason :open?) (rows (att {})))))
    (is (= 1 (count (rows (att {:seon.run/deferred-results [{:seon.proof/test 'seon.root-test/boots :seon.proof/status :pass}]}))))
        "one of two recorded: still open")
    (is (= 1 (count (rows (att {:seon.run/deferred-results [{:seon.proof/test 'seon.root-test/boots :seon.proof/status :pass}
                                                                {:seon.proof/test 'seon.check-test/clean :seon.proof/status :fail}]}))))
        "a red among them: still open")
    (is (= [] (rows (att {:seon.run/deferred-results [{:seon.proof/test 'seon.root-test/boots :seon.proof/status :pass}
                                                         {:seon.proof/test 'seon.check-test/clean :seon.proof/status :pass}]})))
        "each passing: the row closes")
    (is (= [{:ns 'app.repl-only :reason "no :ns/file on the branch: its forms were never written to a file"}]
           (:unread (first (rows (att {:seon.run/deferred-results [{:seon.proof/test 'seon.root-test/boots :seon.proof/status :error}
                                                                       {:seon.proof/test 'seon.check-test/clean :seon.proof/status :error}]
                                       :seon.run/gate-unread [{:ns 'app.repl-only :reason "no :ns/file on the branch: its forms were never written to a file"}]})))))
        "a gate that could not read a changed namespace names it, and why, on the open row")))

;; ---- derived: a dead citation is true while it is true (docs/health.md) ----

(deftest a-deleted-var-makes-its-citation-dead-until-it-is-back
  (let [db (ts/test-db)
        v (:v (first (d/datoms db :aevt :var/name)))
        e (:e (first (d/datoms db :aevt :var/name)))
        none (symbol (namespace v) "no-such-var")
        page (str "# Health\n\nThe check reads `" v "` and `" none "`; a prose word " (namespace v) "/nor-this is no citation.\n")
        db (d/db-with db (seon.docs/load-tx db "demo" {"demo/h.md" page}))
        dead #(get-in (problems/health %) [:classes :dead-citation])]
    (testing "the loader keeps the names the text cites as references: a backticked name, never a prose word naming nothing"
      (is (= #{v none} (set (:seon.section/names (d/pull db [:seon.section/names] [:seon.section/address "demo/h.md#health"]))))))
    (testing "a backticked name that never named anything is dead from its load"
      (is (= [[none "demo/h.md#health"]] (mapv (juxt :name :section) (:rows (dead db))))))
    (let [gone (d/db-with db [[:db/retractEntity e]])]
      (testing "the var deleted: its citation is dead, with what a row needs"
        (let [rows (:rows (dead gone))
              r (first (filter #(= v (:name %)) rows))]
          (is (= 2 (:n (dead gone))))
          (is (= (str "demo/h.md#health cites " v ", which names nothing") (:message r)))
          (is (= (symbol (namespace v)) (:owner-ns r)) "owned by the namespace that lost it")
          (is (contract/valid? :seon.problems/report (problems/health gone)))
          (is (empty? (get-in (problems/report gone) [:classes :dead-citation])) "report is what was recorded: a derived class is health's")))
      (testing "the var back: the citation names it again; nothing was written to close it"
        (let [back (d/db-with gone [{:var/name v}])]
          (is (= [none] (mapv :name (:rows (dead back))))))))))

(defn citing-input
  "The cost proof's input at n: a wiki page of n sections, each citing one of the seed's vars
  (cycling past its count), every cited var then deleted, so every citation is dead."
  [n] (let [db0 (ts/test-db)
            vs (vec (take n (map :v (d/datoms db0 :aevt :var/name))))
            page (apply str "# Cited\n\nThe page.\n" (for [i (range n)] (str "\n## S" i "\n\nIt reads `" (nth vs (mod i (count vs))) "`.\n")))
            db (d/db-with db0 (seon.docs/load-tx db0 "demo" {"demo/c.md" page}))
            cited (into #{} (map :v) (d/datoms db :aevt :seon.section/names))]
        [(d/db-with db (vec (for [s cited :let [e (pdb/eid db :var/name s)] :when e] [:db/retractEntity e]))) {}]))

(deftest dead-citations-follow-the-citations
  (cost/proof {:of 'seon.problems/dead-citations :input 'seon.problems-test/citing-input
               :n "sections, each citing one var, every one deleted (each a dead citation)"
               :work (str "fixed: main's var names read once into a set (the seed's ~300 vars). Per cited name: one set "
                          "lookup; a dead one also runs seon.ref/names-on-db? (an AVET seek for the var, two for a task, "
                          "one for its namespace: ~4 seeks at ~17 us and ~33 KB, seon.db/avet-seek-allocates) and pulls its "
                          "section's address (one pull)")
               :growth :linear
               :per-n {:ns 90000 :bytes 170000 :calls 6}
               :fixed {:ns 2000000 :bytes 3000000 :calls 5}
               :sizes [10 100 1000]}))

(deftest caused-is-what-the-branch-made-true
  (let [db0 (ts/test-db)
        [v w] (map :v (take 2 (d/datoms db0 :aevt :var/name)))
        e (fn [db s] (pdb/eid db :var/name s))
        none (symbol (namespace v) "no-such-var")
        page (str "# P\n\nIt reads `" v "`, `" w "` and `" none "`.\n")
        base (d/db-with db0 (seon.docs/load-tx db0 "demo" {"demo/p.md" page}))]
    (testing "an unchanged branch caused nothing, though base has a dead citation of its own"
      (is (= #{} (problems/caused base base))))
    (testing "the branch deleted v: the citation of v is its; the one base had is not"
      (let [head (d/db-with base [[:db/retractEntity (e base v)]])]
        (is (= #{(str "demo/p.md#p cites " v ", which names nothing")} (problems/caused base head)))
        (testing "and fixed it by restoring v: nothing"
          (is (= #{} (problems/caused base (d/db-with head [{:var/name v}])))))))))

(deftest a-catch-its-page-does-not-name-is-a-law-problem
  ;; bin/laws catch's rule, from the compiler's facts: a live form carrying :form/catches, by
  ;; the var it defines, named by docs/failure-points.md's cited names
  (let [db0 (ts/test-db)
        [fe v] (first (for [{:keys [e]} (d/datoms db0 :aevt :form/defines)
                            :when (seon.program/live? db0 e)
                            d (:form/defines (d/entity db0 e)) :when (:var/name d)]
                        [e (:var/name d)]))
        ;; the test db's program is an example app; make its namespace one of Seon's src
        ns-e (:db/id (:var/ns (d/entity db0 (:e (first (d/datoms db0 :avet :var/name v))))))
        db1 (d/db-with db0 [[:db/add fe :form/catches "{:class Exception, :answers :expr}"] [:db/add ns-e :ns/file "src/app/m.clj"]])
        unnamed #(mapv (juxt :law :site) (get-in (problems/health %) [:classes :unnamed-site :rows]))]
    (is (= [] (unnamed db0)) "no catch, no site")
    (is (= [[:catch v]] (unnamed db1)) "a catch the page does not name")
    (testing "a namespace outside src (an agent's app, a test: a deftest's `is` expands to a catch) is no failure point"
      (is (= [] (unnamed (d/db-with db1 [[:db/add ns-e :ns/file "test/app/x_test.clj"]])))))
    (testing "the page names it: no problem, nothing written to close it"
      (let [page (str "# Failure points\n\n| failure | edge |\n|---|---|\n| x | `" v "` |\n")]
        (is (= [] (unnamed (d/db-with db1 (seon.docs/load-tx db1 "docs" {"docs/failure-points.md" page})))))))))

(deftest a-pin-the-wiki-states-is-the-recorded-gitlink
  (let [db0 (ts/test-db)
        page "# Forks\n\n## Jolt\n\n`reference-code/jolt` pinned at `4342b73c`, the tip of ``jolt/x``.\n\n## Chez\n\n`reference-code/chez` pinned at `80e4b9f`.\n"
        db (d/db-with db0 (seon.docs/load-tx db0 "docs" {"docs/forks.md" page}))
        pinned (fn [pins] (mapv (juxt :path :stated :recorded)
                                (get-in (problems/health (cond-> db pins (d/db-with [{:seon.root/id :root :seon.root/source {:root "/c" :files 1 :forms 1 :ms 1 :pins pins}}])))
                                        [:classes :stale-pin :rows])))]
    (is (= [] (pinned nil)) "no import recorded: unknown, not a mismatch")
    (is (= [] (pinned {"reference-code/jolt" "4342b73cdeadbeef" "reference-code/chez" "80e4b9f0"})))
    (is (= [["reference-code/chez" "80e4b9f" "d19eb3b0"]] (pinned {"reference-code/jolt" "4342b73cdeadbeef" "reference-code/chez" "d19eb3b0"})))
    (is (= [["reference-code/jolt" "4342b73c" nil]] (pinned {"reference-code/chez" "80e4b9f0"})) "a pin with no gitlink")))

(deftest an-error-closes-when-its-subject-changes
  ;; an old store's :seon.docs/refused error (the type left the code) names its link's target in
  ;; its example; a new error may name :seon.error/subject. Neither is ever retracted.
  (let [db0 (ts/test-db)
        refused (fn [db] (d/db-with db [{:seon.error/signature "aaaaaaaaaaaaaaaa" :seon.error/class :platform :seon.error/count 1
                                         :seon.error/agents 0 :seon.error/first-ms 1 :seon.error/last-ms 1
                                         :seon.error/type :seon.docs/refused :seon.error/message "demo/a.md#a links to demo/b.md#b, which is no section"
                                         :seon.error/example {:seon.error/type :seon.docs/refused :seon.ref/address "demo/b.md#b"}}]))
        row #(first (get-in (problems/report %) [:classes :platform :rows]))
        db (refused db0)]
    (testing "the target section is missing: the error is open"
      (is (some? (row db)) "the error has a row") (is (not (false? (:open? (row db))))))
    (let [after (d/db-with db (seon.docs/load-tx db "demo" {"demo/b.md" "# B\n\nThe target.\n"}))]
      (testing "the section exists now: closed, with nothing written to close it"
        (is (some? (pdb/eid after :seon.section/address "demo/b.md#b")))
        (is (false? (:open? (row after))))
        (is (= 1 (:count (row after))) "the record is kept")))
    (testing "the citing section's link is changed to an existing target: closed, nothing written (the target never appears)"
      (let [dbc (d/db-with db0 (seon.docs/load-tx db0 "demo" {"demo/a.md" "# A\n\nIt links to [b](b.md#nowhere).\n" "demo/c.md" "# C\n\nThe other.\n"}))
            err (d/db-with dbc [{:seon.error/signature "cccccccccccccccc" :seon.error/class :platform :seon.error/count 1
                                 :seon.error/agents 0 :seon.error/first-ms 1 :seon.error/last-ms 1 :seon.error/type :seon.docs/refused
                                 :seon.error/example {:seon.error/type :seon.docs/refused :seon.ref/address "demo/b.md#nowhere" :seon.section/address "demo/a.md#a"}}])
            rowc #(first (filter (fn [r] (= "cccccccccccccccc" (:signature r))) (get-in (problems/report %) [:classes :platform :rows])))
            fixed (d/db-with err (seon.docs/load-tx err "demo" {"demo/a.md" "# A\n\nIt links to [c](c.md).\n" "demo/c.md" "# C\n\nThe other.\n"}))]
        (is (some? (rowc err)) "the error has a row") (is (not (false? (:open? (rowc err)))) "the citing section still has the link")
        (is (false? (:open? (rowc fixed))))))
    (testing "a named subject: open until a fact of the var is written after the error"
      (let [v (:v (first (d/datoms db0 :aevt :var/name)))
            e (:e (first (d/datoms db0 :aevt :var/name)))
            err (d/db-with db0 [{:seon.error/signature "bbbbbbbbbbbbbbbb" :seon.error/class :platform :seon.error/count 1
                                 :seon.error/agents 0 :seon.error/first-ms 1 :seon.error/last-ms 1 :seon.error/subject v}])]
        (is (some? (row err)) "the error has a row") (is (not (false? (:open? (row err)))))
        (is (false? (:open? (row (d/db-with err [{:db/id e :var/doc "changed"}])))))))))

;; ---- cost proofs of the derived classes beyond dead-citations ---------------------------------

(defn catch-forms
  "db0 with n more live forms in a src namespace, each defining one var and carrying a catch the
  law page does not name (so each is an :unnamed-site)."
  [db0 n]
  (let [ns-e (:db/id (:var/ns (d/entity db0 (:e (first (d/datoms db0 :aevt :var/name))))))]
    (d/db-with db0 (into [[:db/add ns-e :ns/file "src/app/m.clj"]]
                         (mapcat (fn [i] [{:db/id (- (inc (* 2 i))) :form/id (str "cost-form-" i) :form/ns ns-e :form/order i :form/src "(defn f [])"
                                           :form/catches #{"{:class Exception, :answers :expr}"} :form/defines [(- (* 2 i))]}
                                          {:db/id (- (* 2 i)) :var/name (symbol "app.cost" (str "v" i)) :var/ns ns-e :var/form (- (inc (* 2 i)))}]))
                         (range 1 (inc n))))))

(defn catch-input "The cost proof's input at n: n unnamed catch sites." [n] [(catch-forms (ts/test-db) n) {}])

(defn health-input "The cost proof's input at n: the db of n unnamed catch sites." [n] [(catch-forms (ts/test-db) n)])

(defn pins-input
  "The cost proof's input at n: docs/forks.md of n sections each stating a pin, root's import
  recording a different gitlink for every one (so each is a :stale-pin)."
  [n] (let [db0 (ts/test-db)
            page (apply str "# Forks\n\nThe pins.\n" (for [i (range n)] (str "\n## F" i "\n\n`reference-code/lib" i "` pinned at `abc" i "`.\n")))
            db (d/db-with db0 (seon.docs/load-tx db0 "docs" {"docs/forks.md" page}))]
        [(d/db-with db [{:seon.root/id :root :seon.root/source {:pins (into {} (for [i (range n)] [(str "reference-code/lib" i) (str "def" i)]))}}]) {}]))

(deftest unnamed-sites-follow-the-catch-forms
  (cost/proof {:of 'seon.problems/unnamed-sites :input 'seon.problems-test/catch-input
               :n "live forms carrying a catch, each defining one var, none named by the page"
               :work "once: the page's cited names as a set, the method and impl owners, the program's namespaces (seon.program/program-nss); per site: its form entity, its defines and one row"
               :growth :linear
               :per-n {:ns 76000 :bytes 74500 :calls 3}
               :fixed {:ns 50000 :bytes 50000 :calls 2}
               :sizes [10 100 1000]}))

(deftest stale-pins-follow-the-pin-page
  (cost/proof {:of 'seon.problems/stale-pins :input 'seon.problems-test/pins-input
               :n "sections of docs/forks.md, each stating one stale pin"
               :work "one AVET range of addresses, then per section a pull of its text, one regex scan and one row"
               :growth :linear
               :per-n {:ns 30000 :bytes 30000 :calls 2}
               :fixed {:ns 600000 :bytes 600000 :calls 5}
               :sizes [10 100 1000]}))

(deftest health-follows-what-is-recorded-and-cited
  (cost/proof {:of 'seon.problems/health :input 'seon.problems-test/health-input
               :n "unnamed catch sites (the derived classes dominate; the recorded part is the seed's)"
               :work "report once, then the three derived reads; the sites dominate"
               :growth :linear
               :per-n {:ns 80000 :bytes 80000 :calls 3}
               ;; fixed bytes re-derived 2026-10-23 (jolt/similar-finds-helpers-by-embedding): 3.01 MB at 10,
               ;; 10.0 MB at 100, 80.2 MB at 1,000 sites; a fixed part near the smallest size's whole cost read
               ;; as steeper growth (task seon.cost/a-speedup-never-reads-as-steeper)
               :fixed {:ns 19500000 :bytes 17500000 :calls 62}
               :sizes [10 100 1000]}))

;; ---- caused reads what changed (diff by value), and agrees with the full reading ---------------

(defn- caused-full
  "The old `caused`: both derived classes read in full over base and over head."
  [base head]
  (let [derived #(concat (problems/dead-citations % {}) (problems/unnamed-sites % {}))
        before (into #{} (map :signature) (derived base))]
    (into (sorted-set) (comp (remove (comp before :signature)) (map :message)) (derived head))))

(deftest caused-agrees-with-the-full-reading
  (let [db0 (ts/test-db)
        [v w] (map :v (take 2 (d/datoms db0 :aevt :var/name)))
        e (fn [db s] (pdb/eid db :var/name s))
        none (symbol (namespace v) "no-such-var")
        page (str "# P\n\nIt reads `" v "`, `" w "` and `" none "`.\n\n## Q\n\nAlso `" v "`.\n")
        base (d/db-with db0 (seon.docs/load-tx db0 "demo" {"demo/p.md" page}))
        base (catch-forms base 3)
        failure-points "# Failure points\n\n| x | `app.cost/v1` |\n"
        scenarios
        {"unchanged" base
         "a var both sections cite is deleted" (d/db-with base [[:db/retractEntity (e base v)]])
         "the var is back" (d/db-with (d/db-with base [[:db/retractEntity (e base v)]]) [{:var/name v}])
         "a section is added citing a dead name" (d/db-with base (seon.docs/load-tx base "demo" {"demo/p.md" page "demo/r.md" (str "# R\n\nIt reads `" none "`.\n")}))
         "a section is rewritten to cite a var that exists" (d/db-with base (seon.docs/load-tx base "demo" {"demo/p.md" (str "# P\n\nIt reads `" w "`.\n")}))
         "a catch site is added" (catch-forms base 4)
         "the failure-points page names a site" (d/db-with base (seon.docs/load-tx base "docs" {"docs/failure-points.md" failure-points}))
         "the page names it, then a var it cites is deleted" (let [b2 (d/db-with base (seon.docs/load-tx base "docs" {"docs/failure-points.md" failure-points}))]
                                                              (d/db-with b2 [[:db/retractEntity (e b2 'app.cost/v1)]]))}]
    (doseq [[label head] scenarios]
      (testing label
        (is (= (caused-full base head) (problems/caused base head)))
        (is (= (problems/caused base head)
               (problems/caused base head (seon.merge/delta-changes base head #{}) (diff base head)))
            "given the whole diff, the same rows: the datoms it holds beyond caused-datoms' are not read")))
    (testing "and the cases are not all empty"
      (is (seq (problems/caused base (get scenarios "a var both sections cite is deleted"))))
      (is (seq (problems/caused base (get scenarios "a catch site is added")))))))

(defn caused-flat-input
  "The cost proof's input at n: [base head changes moved] over a program of n catch sites and n citing
  sections, the head one section rewritten to cite a dead name and one more catch site: a fixed
  change, its changes since base and the datoms that differ read as the turn and the writer hold them
  (seon.merge/delta-changes and the diff, read once: their cost is proved at
  seon.program-cost-test/a-turns-diff-reads-what-the-branch-changed)."
  [n] (let [db0 (ts/test-db)
            vs (vec (take 10 (map :v (d/datoms db0 :aevt :var/name))))
            page (apply str "# Cited\n\nThe page.\n" (for [i (range n)] (str "\n## S" i "\n\nIt reads `" (nth vs (mod i 10)) "`.\n")))
            base (catch-forms (d/db-with db0 (seon.docs/load-tx db0 "demo" {"demo/c.md" page})) n)
            changed (str/replace page "## S0\n\nIt reads `" (str "## S0\n\nIt reads `app.nowhere/dead` and `"))
            head (catch-forms (d/db-with base (seon.docs/load-tx base "demo" {"demo/c.md" changed})) (inc n))]
        [base head (seon.merge/delta-changes base head #{}) (problems/caused-datoms base head)]))

(deftest caused-follows-what-changed
  ;; the program grows (n sections citing, n catch sites); the change is fixed (one section, one
  ;; site): the work is the scan of the attributes' datoms, a few bytes each, never a pull or a rule
  ;; per citation or site. Before: ~150 KB per catch site, both dbs read in full. 24 contracted calls were its three
  ;; classes; 32 since split-rows (1) and the old-word class, which reads the table and the changed texts on each side (6).
  ;; The input holds the branch's changes (ch), as a caller does: the 1.5 M ticks and 40 calls of the program diff are not caused's.
  (cost/proof {:of 'seon.problems/caused :input 'seon.problems-test/caused-flat-input
               :n "sections citing a var and catch sites in the program, one of each changed"
               :work "the datoms that differ (given), one entity seek per section, name, form and task they touch on each side, the rule for the candidates only; the sections, vars and catch sites the branch did not touch are not read"
               :growth :constant
               :per-n {}
               ;; measured 2026-10-29: ~0.45M ticks / 1.8 MB / 48 calls at 10, 100 and 1000 given the diff (the old-word table
               ;; read on each side is most of it); before, 0.69M / 2.8 MB / 37 at 10 and linear (docs/cost.md#a-diff-per-attribute-costs-a-descent-each)
               :fixed {:ticks 160000 :bytes 1500000 :calls 60}
               :sizes [10 100 1000]}))

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

(deftest an-old-word-is-a-problem
  ;; task seon.problems/an-old-word-is-a-problem: a place that uses a word of AGENTS.md section 7a's table is a row
  ;; naming it and the word, found for the places a branch changed and failing its health step; a kept file is not read
  (let [base (ts/test-db)
        page (fn [text] {"demo/p.md" (str "# P\n\nThe lead.\n\n## Part\n\n" text "\n")})
        with (fn [db text] (d/db-with db (seon.docs/load-tx db "demo" (page text))))
        row (fn [db] (set (map (juxt :id :word) (get-in (problems/health db) [:classes :old-word :rows]))))]
    (testing "a section that says lane"
      (let [head (with base "A lane of work.")]
        (is (= #{["demo/p.md#part" "lane"]} (row head)) "the section and the word")
        (is (= [{:class :old-word :message "demo/p.md#part (demo/p.md) uses the old word \"lane\": AGENTS.md section 7a says what to say instead"}]
               (:rows (problems/caused-split base head))) "the branch that added it caused it")
        (is (seq (:caused (problems/caused-split base head))) "the done rule refuses")
        (is (empty? (row (with head "A worktree session."))) "editing it closes the row")))
    (testing "a task plan that says verb, and a clean one"
      (let [t (fn [plan] {:seon.task/id "demo/x" :seon.task/ns 'demo :seon.task/name "x" :seon.task/plan plan :seon.task/execution :held :seon.task/branch :b :seon.task/kind :work})
            head (d/db-with base [(t "Add a verb for it.")])]
        (is (= #{["demo/x" "verb"]} (row head)))
        (is (empty? (row (d/db-with base [(t "Add a function for it.")]))))))
    (testing "a word as part of another word is no use of it"
      (is (empty? (row (with base "The landscape and a laneway.")))))))

(deftest health-moved-is-health
  ;; health-moved carries prev's rows and reads where base and head differ: for each kind of move
  ;; of main it equals health read whole (set of rows by class; the row order is the scan's)
  (let [db0 (ts/test-db)
        [v w] (map :v (take 2 (d/datoms db0 :aevt :var/name)))
        page (str "# P\n\nIt reads `" v "` and `" w "`.\n")
        db1 (d/db-with db0 (seon.docs/load-tx db0 "demo" {"demo/p.md" page}))
        same? (fn [base head]
                (let [moved (problems/health-moved (problems/health base) base head (diff base head)) whole (problems/health head)]
                  (and (= (set (keys (:classes whole))) (set (keys (:classes moved))))
                       (every? #(= (set (get-in whole [:classes % :rows])) (set (get-in moved [:classes % :rows])))
                               (keys (:classes whole))))))
        task {:seon.task/id "t/x" :seon.task/ns 't :seon.task/name "x" :seon.task/plan "p" :seon.task/execution :running :seon.task/branch :b :seon.task/kind :work :seon.task/proofs #{'t-test/x}}
        run (fn [n status] {:seon.run/id (str "t/x#" n) :seon.run/task [:seon.task/id "t/x"] :seon.run/boot "b" :seon.run/agent {:seon.agent/key "b/a1" :seon.agent/id :a1}
                            :seon.run/start-ms 1 :seon.run/end-ms n :seon.run/branch :b :seon.run/max-turns 2 :seon.run/status status})]
    (is (same? db1 db1) "nothing moved")
    (testing "an old word added to a section opens a row, and editing it closes it"
      (let [bad (d/db-with db1 (seon.docs/load-tx db1 "demo" {"demo/p.md" (str page "\nA lane of work.\n")}))
            fixed (d/db-with bad (seon.docs/load-tx bad "demo" {"demo/p.md" (str page "\nA worktree session.\n")}))]
        (is (same? db1 bad))
        (is (pos? (get-in (problems/health-moved (problems/health db1) db1 bad (diff db1 bad)) [:classes :old-word :n])))
        (is (same? bad fixed))))
    (testing "a run that died opens a problem, and a later run that finished its task closes it"
      (let [db2 (d/db-with db1 [task (assoc (run 1 :dead) :seon.run/reason "its agent died" :seon.run/error (contract/error :seon.agent/died 'seon.root/end-agent "agent a1 died" {:agent :a1}))])
            db3 (d/db-with db2 [(assoc (run 2 :merged) :seon.run/outcome :green)])]
        (is (same? db1 db2))
        (is (pos? (get-in (problems/health-moved (problems/health db1) db1 db2 (diff db1 db2)) [:classes :dead :n])))
        (is (same? db2 db3))
        (is (zero? (get-in (problems/health-moved (problems/health db2) db2 db3 (diff db2 db3)) [:classes :dead :n])))))
    (testing "a docstring changed under the text opens a stale section"
      (let [db2 (change-meaning db1 v)]
        (is (same? db1 db2))
        (is (= 1 (get-in (problems/health-moved (problems/health db1) db1 db2 (diff db1 db2)) [:classes :stale-section :n])))
        (testing "and editing the section closes it"
          (is (same? db2 (d/db-with db2 (seon.docs/load-tx db2 "demo" {"demo/p.md" (str page "\nMore.\n")})))))))
    (testing "a cited name added to a section opens a dead citation, a deleted var opens another"
      (let [dead (d/db-with db1 (seon.docs/load-tx db1 "demo" {"demo/p.md" (str page "\nAnd `" (namespace v) "/no-such-var`.\n")}))]
        (is (same? db1 dead))
        (is (pos? (get-in (problems/health-moved (problems/health db1) db1 dead (diff db1 dead)) [:classes :dead-citation :n])))
        (is (same? dead db1))
        (is (same? dead (d/db-with dead [[:db/retractEntity (pdb/eid dead :var/name v)]])) "a deleted var opens more")
        (testing "and a var added that an unchanged section cites closes it"
          (let [back (d/db-with dead [{:var/name (symbol (namespace v) "no-such-var")}])]
            (is (same? dead back))
            (is (< (get-in (problems/health-moved (problems/health dead) dead back (diff dead back)) [:classes :dead-citation :n])
                   (get-in (problems/health dead) [:classes :dead-citation :n])))))))
    (testing "a section added that an unchanged section links to closes its dead link"
      (let [linking (d/db-with db1 (seon.docs/load-tx db1 "demo" {"demo/p.md" (str page "\nSee [q](q.md).\n")}))
            both (d/db-with linking (seon.docs/load-tx linking "demo" {"demo/p.md" (str page "\nSee [q](q.md).\n") "demo/q.md" "# Q\n\nLead.\n"}))]
        (is (same? db1 linking))
        (is (same? linking both))))
    (testing "an answer added to an untouched run's row is carried current"
      (let [db2 (d/db-with db1 [task (assoc (run 1 :dead) :seon.run/reason "its agent died" :seon.run/error (contract/error :seon.agent/died 'seon.root/end-agent "agent a1 died" {:agent :a1}))])
            db3 (d/db-with db2 [{:seon.task/id "fix/x" :seon.task/ns 'fix :seon.task/name "x" :seon.task/plan "p" :seon.task/execution :held
                                 :seon.task/branch :f :seon.task/kind :work :seon.task/evidence {:kind :run :from "t/x#1"}}])
            moved (problems/health-moved (problems/health db2) db2 db3 (diff db2 db3))
            whole (problems/health db3)]
        (is (= (get-in whole [:classes :dead :rows]) (get-in moved [:classes :dead :rows])))))))

(deftest a-section-is-stale-when-the-code-it-cites-changed-after-its-text
  (let [db0 (ts/test-db)
        [v w] (map :v (take 2 (d/datoms db0 :aevt :var/name)))
        page (str "# P\n\nIt reads `" v "`.\n")
        db1 (d/db-with db0 (seon.docs/load-tx db0 "demo" {"demo/p.md" page}))
        stale #(get-in (problems/health %) [:classes :stale-section])
        read #(:seon.section/read (d/pull % [:seon.section/read] [:seon.section/address "demo/p.md#p"]))]
    (testing "the loader records the form id of each var the text cites"
      (is (= {v (form-id-of db1 v)} (read db1))))
    (testing "an unchanged wiki writes nothing, the record kept"
      (is (= [] (seon.docs/load-tx db1 "demo" {"demo/p.md" page}))))
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
        (is (= 1 (:n (stale (d/db-with db2 (seon.docs/load-tx db2 "demo" {"demo/p.md" page})))))))
      (testing "editing the section re-reads the code: closed"
        (is (zero? (:n (stale (d/db-with db2 (seon.docs/load-tx db2 "demo" {"demo/p.md" (str page "\nMore words.\n")})))))))
      (testing "a review closes it with the text untouched, and the review is kept"
        (let [db3 (d/db-with db2 (seon.docs/reviewed-tx db2 "demo/p.md#p" "sean"))]
          (is (zero? (:n (stale db3))))
          (is (= "sean" (:by (:seon.section/reviewed (d/pull db3 [:seon.section/reviewed] [:seon.section/address "demo/p.md#p"])))))
          (is (= (:seon.section/text (d/pull db2 [:seon.section/text] [:seon.section/address "demo/p.md#p"]))
                 (:seon.section/text (d/pull db3 [:seon.section/text] [:seon.section/address "demo/p.md#p"])))))))
    (testing "it is attention, never a reason to refuse done: a branch that changes the code sees it, and the done rule does not"
      (let [head (change-meaning db1 v) {:keys [caused attention]} (problems/caused-split db1 head)]
        (is (= [] caused))
        (is (= 1 (count attention)))
        (is (re-find #"demo/p.md#p describes" (first attention)))
        (is (= #{} (problems/caused db1 head)))
        (is (contains? (:seon.problems/attention (contract/properties :seon.problems/class)) :stale-section))))))

(deftest a-page-nothing-links-to-is-an-orphan-until-something-does
  (let [db0 (ts/test-db)
        pages {"docs/README.md" "# Map\n\nThe map.\n\n- [a](a.md)\n" "docs/a.md" "# A\n\nThe page A.\n" "docs/b.md" "# B\n\nThe page B.\n"}
        db (d/db-with db0 (seon.docs/load-tx db0 "docs" pages))
        orphans #(mapv :page (get-in (problems/health %) [:classes :orphan-page :rows]))]
    (is (= ["docs/b.md"] (orphans db)) "the map is the root; a is linked by it; b by nothing")
    (let [linked (d/db-with db (seon.docs/load-tx db "docs" (assoc pages "docs/a.md" "# A\n\nSee [b](b.md).\n")))]
      (is (= [] (orphans linked)) "another page links it: closed, nothing written to close it"))
    (is (= [] (orphans (d/db-with db0 (seon.docs/load-tx db0 "docs" (dissoc pages "docs/README.md")))))
        "no map in the db: unknown, not every page an orphan")))

(deftest a-section-with-no-lead-is-a-problem
  (let [db0 (ts/test-db)
        page "# A\n\nThe page A.\n\n## Listed\n\n- one\n- two\n\n## Said\n\nA sentence that says what it is.\n"
        db (d/db-with db0 (seon.docs/load-tx db0 "docs" {"docs/a.md" page}))
        rows #(mapv :section (get-in (problems/health %) [:classes :leadless-section :rows]))]
    (is (= ["docs/a.md#listed"] (rows db)) "a section whose first block is a list has no lead")
    (let [fixed (d/db-with db (seon.docs/load-tx db "docs" {"docs/a.md" (str/replace page "- one" "It holds two items.\n\n- one")}))]
      (is (= [] (rows fixed)) "a paragraph before the list closes it, with nothing else written"))
    (is (contains? (:seon.problems/derived (contract/properties :seon.problems/class)) :leadless-section))))

(defn leadless-input
  "The cost proof's input at n: n sections, none with a lead."
  [n] (let [db0 (ts/test-db)]
        [(d/db-with db0 (seon.docs/load-tx db0 "docs" {"docs/p.md" (apply str "# P\n\nThe page.\n" (for [i (range n)] (str "\n## S" i "\n\n- item\n")))})) {}]))

(deftest leadless-sections-follow-the-sections
  (cost/proof {:of 'seon.problems/leadless-sections :input 'seon.problems-test/leadless-input
               :n "sections, none with a lead"
               :work "the sections' addresses and the lead datoms once, sorted by address, then a row per section"
               :growth :linear
               :per-n {:bytes 8500 :calls 1}
               :fixed {:bytes 9000 :calls 1}
               :sizes [10 100 1000]}))

(deftest an-issue-with-no-page-is-a-problem
  (let [db0 (ts/test-db)
        err {:seon.error/signature "eeeeeeeeeeeeeeee" :seon.error/class :platform :seon.error/count 1 :seon.error/agents 0
             :seon.error/first-ms 1 :seon.error/last-ms 1 :seon.error/type :seon.watch/died}
        unlinked #(filterv #{:platform} (mapv :class (get-in (problems/health %) [:classes :problem-unlinked :rows])))
        with-err (d/db-with db0 [err])]
    (is (= [] (unlinked with-err)) "no wiki loaded: unknown")
    (let [wiki (d/db-with with-err (seon.docs/load-tx with-err "docs" {"docs/README.md" "# Map\n\nThe map.\n"}))]
      (is (= [:platform] (unlinked wiki)) "a wiki without the class's section: the issue points nowhere")
      (let [fixed (d/db-with wiki (seon.docs/load-tx wiki "docs" {"docs/README.md" "# Map\n\nThe map.\n" "docs/errors.md" "# Errors\n\n## Problems\n\nHow they are read.\n"}))]
        (is (= [] (unlinked fixed)) "the section exists: closed, nothing written")))))

(deftest every-problem-class-points-at-a-section-of-the-real-wiki
  (let [docs (:seon.problems/docs (contract/properties :seon.problems/class))
        db0 (ts/test-db)
        db (d/db-with db0 (seon.docs/load-dir-tx db0 (seon.config/checkout) "docs"))]
    (is (every? docs (:seon.problems/derived (contract/properties :seon.problems/class))) "each derived class declares its section")
    (doseq [[c a] docs]
      (is (some? (pdb/eid db :seon.section/address a)) (str c " points at " a ", which is no section of the wiki")))))

(defn stale-input
  "The cost proof's input at n: n sections each citing a var, every var's docstring then changed (each section stale)."
  [n] (let [db0 (ts/test-db)
            vs (vec (take n (map :v (d/datoms db0 :aevt :var/name))))
            page (apply str "# Cited\n\nThe page.\n" (for [i (range n)] (str "\n## S" i "\n\nIt reads `" (nth vs (mod i (count vs))) "`.\n")))
            db (d/db-with db0 (seon.docs/load-tx db0 "demo" {"demo/c.md" page}))]
        [(reduce change-meaning db (distinct vs)) {}]))

(deftest stale-sections-follow-the-recorded-names
  (cost/proof {:of 'seon.problems/stale-sections :input 'seon.problems-test/stale-input
               :n "sections each citing a var whose docstring then changed"
               :work "the read, review, vars' and forms' datoms in one scan each, then per recorded name whose form moved a seek of its var, its name's history and each meaning attribute's history (value-at), and a row"
               :growth :linear
               :per-n {:ns 90000 :bytes 120000 :calls 4}
               :fixed {:ns 2500000 :bytes 4000000 :calls 3}
               :sizes [10 100 300]}))

(defn orphan-input
  "The cost proof's input at n: the map and n pages, none linked."
  [n] (let [db0 (ts/test-db)]
        [(d/db-with db0 (seon.docs/load-tx db0 "docs" (into {"docs/README.md" "# Map\n\nThe map.\n"} (for [i (range n)] [(str "docs/p" i ".md") (str "# P" i "\n\nThe page.\n")])))) {}]))

(deftest orphan-pages-follow-the-sections
  (cost/proof {:of 'seon.problems/orphan-pages :input 'seon.problems-test/orphan-input
               :n "pages, none linked"
               :work "the sections' addresses and the reference datoms once, then a row per orphan"
               :growth :linear
               :per-n {:ns 30000 :bytes 30000 :calls 1}
               :fixed {:ns 500000 :bytes 500000 :calls 3}
               :sizes [10 100 1000]}))

(defn unlinked-input
  "The cost proof's input at n: a db with a wiki, and every class holding n open rows."
  [n] (let [db0 (ts/test-db)
            db (d/db-with db0 (seon.docs/load-tx db0 "docs" {"docs/README.md" "# Map\n\nThe map.\n"}))
            cs (:seon.problems/docs (contract/properties :seon.problems/class))]
        [db {} (into {} (for [c (keys cs)] [c {:n n :rows (vec (repeat n {:open? true}))}]))]))

(deftest problem-unlinked-follows-the-classes-not-their-rows
  (cost/proof {:of 'seon.problems/problem-unlinked :input 'seon.problems-test/unlinked-input
               :n "open rows in each class"
               :work "one lookup per class with an open row, found at the first row"
               :growth :constant
               :per-n {:ns 0 :bytes 0 :calls 0}
               ;; 47: the :stalled class (seon.problems/stalled-agents) is one more class to look up;
               ;; 49: :flaky (seon.proofs/flaky) one more
               :fixed {:ns 3000000 :bytes 4000000 :calls 51}
               :sizes [10 100 1000]}))

(deftest attention-survives-a-store-write
  ;; the turn records what it observed in its basis; a sorted set cannot be written to the store
  ;; (konserve's fasl), which ended every scripted turn's agent. caused-split answers vectors.
  (let [db0 (ts/test-db)
        [v w] (map :v (take 2 (d/datoms db0 :aevt :var/name)))
        base (d/db-with db0 (seon.docs/load-tx db0 "demo" {"demo/p.md" (str "# P\n\nIt reads `" v "`.\n")}))
        head (change-meaning base v)
        {:keys [caused attention]} (problems/caused-split base head)]
    (is (seq attention) "the branch made a section stale")
    (is (vector? attention))
    (is (vector? caused))
    (is (= {:attention attention :caused caused}
           (konserve.jolt.fasl/decode (konserve.jolt.fasl/encode {:attention attention :caused caused}))) "and it is written to the store and read back")))

(defn- uncited-fixture
  "A db of design/implement pairs for the uncited-task proofs: [db rows-fn]. Each pair is
  [name design-plan & more design keys]; every design step has a finished run (its implement step
  is ready) unless named in not-done."
  [pairs not-done]
  (let [db (ts/test-db)
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
        done (first (:seon.status/finished (contract/properties :seon.run/status)))
        tx (seon.task/create-tx db0 (vec (mapcat pair pairs)))]
    (d/db-with db0 (concat tx [{:db/id -1 :var/name 'app-test/stub :var/form -2} {:db/id -2 :form/id "f-stub" :form/src stub}
                               {:db/id -3 :var/name 'app-test/real :var/form -4} {:db/id -4 :form/id "f-real" :form/src "(deftest real (is (= 1 1)))"}]
                              (for [[n] pairs :when (not (not-done n))]
                                {:seon.run/task [:seon.task/id (str "app/" n)] :seon.run/status done})))))

(defn uncited-input
  "The cost proof's input at n: n design steps (a finished run, a plan citing a var) and an
  implement step each, all ready and fine."
  [n] (let [v (:v (first (d/datoms (ts/test-db) :aevt :var/name)))]
        [(uncited-fixture (for [i (range n)] [(str "t" i) (str "It changes `" v "`.")]) #{}) {}]))

(deftest uncited-tasks-follow-the-ready-implement-steps
  (cost/proof {:of 'seon.problems/uncited-tasks :input 'seon.problems-test/uncited-input
               :n "ready implement steps, each citing"
               :work "the kind and task datoms once, then each implement step's needs walked once"
               :growth :linear
               :per-n {:ns 100000 :bytes 130000 :calls 4}
               :fixed {:ns 500000 :bytes 500000 :calls 3}
               :sizes [10 100 300]}))

(deftest a-ready-implement-task-that-cites-nothing-is-a-problem
  ;; the context is only as good as the plan it is built from: an implement step whose accumulated
  ;; plan (its design step's) cites no var, attribute or section reaches no reference, so the walk
  ;; pulls nothing (task seon.render/spend-has-a-render, 2026-10-23). A design step's prose plan is
  ;; its input, never red.
  (let [v (:v (first (d/datoms (ts/test-db) :aevt :var/name)))
        cited (str "It changes `" v "`.")
        pairs [["cites" cited] ["prose" "Something should be shown somewhere."]
               ["stubbed" cited :seon.task/proofs #{'app-test/stub}] ["bare" cited :seon.task/proofs #{}]
               ["waits" "Prose, but its design step is not done."] ["real" cited :seon.task/proofs #{'app-test/real}]]
        db (uncited-fixture pairs #{"waits"})
        rows #(into #{} (map (juxt (comp :task :evidence) (comp :reason :evidence))) (get-in (problems/health %) [:classes :uncited-task :rows]))]
    (is (= #{["app/prose-impl" :no-references] ["app/stubbed-impl" :placeholder-proof] ["app/bare-impl" :no-proofs]} (rows db))
        "an implement step citing nothing, one whose proof is the stub, one with no proof; design steps' prose plans and an implement step whose design is not done are not judged; a real proof is fine")
    (testing "each closes with nothing written to close it"
      (let [fixed [["cites" cited] ["prose" (str "Show `" v "` somewhere.")] ["stubbed" cited :seon.task/proofs #{'app-test/real}]
                   ["bare" cited :seon.task/proofs #{'app-test/real}] ["waits" "Prose, but its design step is not done."] ["real" cited :seon.task/proofs #{'app-test/real}]]]
        (is (= #{} (rows (uncited-fixture fixed #{"waits"}))))))
    (is (contains? (:seon.problems/derived (contract/properties :seon.problems/class)) :uncited-task))))

(defn- var-forms
  "db0 with the vars app.cite/v0..: each in a src namespace, its form src, docstring and kind as given
  by (spec i) -> {:src :doc :kind}."
  [db0 n spec]
  (let [ns-e (:db/id (:var/ns (d/entity db0 (:e (first (d/datoms db0 :aevt :var/name))))))
        nsym (:ns/name (d/entity db0 ns-e))]
    (d/db-with db0 (into [[:db/add ns-e :ns/file "src/app/cite.clj"]]
                         (mapcat (fn [i] (let [{:keys [src doc kind]} (spec i)]
                                           [{:db/id (- (inc (* 2 i))) :form/id (str "cite-form-" i) :form/ns ns-e :form/order i :form/src src :form/defines [(- (* 2 i))]}
                                            (cond-> {:db/id (- (* 2 i)) :var/name (symbol (str nsym) (str "v" i)) :var/ns ns-e :var/form (- (inc (* 2 i))) :var/kind (or kind :fn)}
                                              doc (assoc :var/doc doc))]))
                                 (range 1 (inc n)))))))

(defn- cite-ns "The namespace var-forms' vars are in: the test db's first var's." [db0]
  (str (:ns/name (:var/ns (d/entity db0 (:e (first (d/datoms db0 :aevt :var/name))))))))

(defn uncited-var-input
  "The cost proof's input at n: n public vars with a docstring citing nothing, named for the check."
  [n] [(var-forms (ts/test-db) n (fn [_] {:src "(defn f [])" :doc "Does a thing."})) {}
       (into #{} (map #(symbol (cite-ns (ts/test-db)) (str "v" %))) (range 1 (inc n)))])

;; See docs/health.md#a-function-that-cites-no-section
(deftest a-public-var-cites-the-section-that-describes-it
  (let [db (var-forms (ts/test-db) 7
                      (fn [i] (case (int i)
                                7 {:src "(seon.contract/defn n [])" :doc "Does a thing."}
                                1 {:src "(defn f [])" :doc "Does a thing."}
                                2 {:src "(defn g [])" :doc "Does a thing. See docs/health.md#a-dead-citation."}
                                3 {:src "(defn- h [])" :doc "A private thing."}
                                4 {:src "(defn k [])"}
                                5 {:src "(deftest t [])" :doc "A test." :kind :test}
                                6 {:src "(defn m [])" :doc "See docs/errors.md#problems."})))
        v #(symbol (cite-ns db) (str "v" %))
        rows #(mapv (comp :var :evidence) (problems/uncited-vars db {} (into #{} (map v) %)))]
    (is (= [(v 1) (v 4) (v 7)] (rows [1 2 3 4 5 6 7])) "a documented public var citing nothing and an undocumented one, and one defined by a namespaced defn; a cited one, a private one and a test are not")
    (is (= [] (problems/uncited-vars db {} #{})) "no changed var, nothing read")
    (is (= [] (problems/uncited-vars db {} #{'app.nowhere/x})) "a name no var has")
    (testing "attention: a branch that adds an uncited public var is told, never refused (AGENTS section 4), and a var the base already left uncited is not its problem"
      (let [base (var-forms (ts/test-db) 1 (fn [_] {:src "(defn f [])" :doc "Does a thing."}))
            head (var-forms (ts/test-db) 2 (fn [i] {:src "(defn f [])" :doc "Does a thing."}))
            split (problems/caused-split base head)]
        (is (some #(re-find #"/v2 is public" %) (:attention split)))
        (is (empty? (:caused split)) "and it refuses nothing")
        (is (not-any? #(re-find #"/v1 is public" %) (:attention split)))))
    (is (contains? (:seon.problems/derived (contract/properties :seon.problems/class)) :uncited-var))))

;; See docs/health.md#a-function-that-cites-no-section
(deftest uncited-vars-follow-the-vars-asked-for
  (cost/proof {:of 'seon.problems/uncited-vars :input 'seon.problems-test/uncited-var-input
               :n "changed public vars, each with a docstring that cites nothing"
               :work "once: the program's namespaces (seon.program/program-nss); per named var: one AVET seek, its entity, its docstring and its form's head (read by seon.source/first-head), and one row"
               :growth :linear
               ;; measured 2026-10-27: 130k ticks / 406 KB / 22 calls at 10, 3.87M / 11.9 MB / 602 at 300;
               ;; 2026-10-29, the form's head read by seon.source/first-head (one contracted call per var): 32 calls at 10
               :per-n {:ticks 13000 :bytes 39500 :calls 3}
               :fixed {:ticks 10000 :bytes 30000 :calls 2}
               :sizes [10 100 1000]}))


