(ns seon.reconcile-test
  "(actions-for db), index then actions, answers exactly the expected actions for a table of task graphs: needs
  DAGs, max-running, held and cancelled tasks, environment changes, a previous root's
  runs, ended runs judged by the done rule, dead agents."
  (:require [seon.test-store :as ts] [seon.profile :as profile] [clojure.test :refer [deftest is are testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.task :as task]
            [seon.reconcile :as r]
            [seon.template :as tpl] [seon.problems :as problems] [seon.merge :as pm] [seon.sock :as sock] [clojure.edn] [seon.contract :as contract]
            [clojure.string :as str] [clojure.set] [seon.config :as cfg] [seon.edn :as sedn]
            [seon.program :as program] [seon.writer :as writer] [seon.turn :as turn] [clojure.core.async :as a] [seon.cost :as cost]))

(defn- empty-db [] (ts/test-db))

(defn- actions-for "The actions that move db toward its desired state: index, then actions." [db] (r/actions (r/index db)))

(defn- decl [nm & {:keys [needs proofs model]}]
  (cond-> {:seon.task/ns 'demo :seon.task/name nm :seon.task/plan (str "Make " nm " true.")
           :seon.task/proofs (or proofs #{(symbol "demo-test" (str nm "-test"))})}
    needs (assoc :seon.task/needs (set (map #(str "demo/" %) needs)))
    model (assoc :seon.task/model model)))

(def diamond [(decl "a") (decl "b" :needs ["a"]) (decl "c" :needs ["a"]) (decl "d" :needs ["b" "c"])])

(defn- run
  "A run fact of task nm: status, plus members (results as {test status}; conflicts
  as :seon.merge/conflict values; unmerged as printed keys)."
  [nm n status & {:keys [boot model results outcome conflicts unmerged changed caused plan deferred deferred-results] :or {boot "boot-1" plan (str "Make " nm " true.")}}]
  (let [id (str "demo/" nm) aid (str id "#" n)]
    (into (task/run-start-tx aid id boot (keyword (str "a-" nm n))
                                 {:branch (task/default-branch id) :model model :max-turns (cfg/value :seon.config.agent/max-turns)} 1000 plan)
          [(cond-> {:seon.run/id aid :seon.run/status status}
             results (assoc :seon.run/results (vec (for [[t s] results] {:seon.proof/test t :seon.proof/status s})))
             outcome (assoc :seon.run/outcome outcome)
             conflicts (assoc :seon.run/conflicts (mapv pm/conflict-tx conflicts))
             unmerged (assoc :seon.run/unmerged unmerged)
             changed (assoc :seon.run/changed changed)
             caused (assoc :seon.run/caused caused)
             deferred (assoc :seon.run/deferred (vec deferred))
             deferred-results (assoc :seon.run/deferred-results (vec (for [[t s] deferred-results] {:seon.proof/test t :seon.proof/status s}))))])))

(defn- db-of
  "A main db: decls written, the ids in `released` released (all when :all), root's boot,
  then extra tx-data (runs, execution changes)."
  [decls & {:keys [released max-running extra] :or {released :all max-running 4}}]
  (let [db (empty-db)
        db (d/db-with db (task/create-tx db decls))
        ids (if (= :all released) (map #(task/task-id 'demo (:seon.task/name %)) decls) released)]
    (cond-> (-> db (d/db-with (task/execution-tx (vec ids) :running))
                (d/db-with (task/boot-tx "boot-1" max-running)))
      (seq extra) (d/db-with extra))))

(defn- merged [nm] (run nm 1 :merged))
(defn- spawn [nm n & {:keys [model]}]
  {:op :spawn :task (str "demo/" nm) :run (str "demo/" nm "#" n)
   :env {:branch (task/default-branch (str "demo/" nm)) :model model :max-turns (cfg/value :seon.config.agent/max-turns)}})
(defn- t [nm] (symbol "demo-test" (str nm "-test")))

(deftest task-state-is-what-happened
  ;; the one derivation of a task's observed state, from the index (seon.render.proc/task-rows reads it)
  (let [db (db-of diamond :extra (into (merged "a") (concat (run "b" 1 :done) (run "c" 1 :running))))
        idx (r/index db)]
    (is (= {"demo/a" :merged "demo/b" :done "demo/c" :running "demo/d" :blocked}
           (into {} (for [id ["demo/a" "demo/b" "demo/c" "demo/d"]] [id (r/task-state idx id)]))))
    (is (= :held (r/task-state (r/index (db-of diamond :released [])) "demo/a")) "never released")))

(deftest needs-dag-starts-in-dependency-order
  (are [db expected] (= expected (actions-for db))
    ;; nothing done: only the root of the diamond can start
    (db-of diamond) [(spawn "a" 1)]
    ;; a merged: b and c start, d waits for both
    (db-of diamond :extra (merged "a")) [(spawn "b" 1) (spawn "c" 1)]
    ;; b merged, c not yet: d still waits
    (db-of diamond :extra (concat (merged "a") (merged "b"))) [(spawn "c" 1)]
    ;; b and c merged: d starts
    (db-of diamond :extra (concat (merged "a") (merged "b") (merged "c"))) [(spawn "d" 1)]
    ;; all merged: nothing to do
    (db-of diamond :extra (mapcat merged ["a" "b" "c" "d"])) []))

(deftest max-running-caps-parallel-runs
  (let [three [(decl "x") (decl "y") (decl "z")]]
    (are [db expected] (= expected (actions-for db))
      (db-of three :max-running 1) [(spawn "x" 1)]
      (db-of three :max-running 2) [(spawn "x" 1) (spawn "y" 1)]
      ;; x running: one slot left of two
      (db-of three :max-running 2 :extra (run "x" 1 :running)) [(spawn "y" 1)]
      ;; two running: none left
      (db-of three :max-running 2 :extra (concat (run "x" 1 :running) (run "y" 1 :running))) [])))

(deftest nothing-autoruns-held-and-cancelled
  (let [three [(decl "x") (decl "y") (decl "z")]]
    (are [db expected] (= expected (actions-for db))
      ;; written tasks are held
      (db-of three :released []) []
      ;; only the released one starts
      (db-of three :released ["demo/y"]) [(spawn "y" 1)]
      ;; cancelled after release
      (db-of three :released ["demo/y"] :extra (task/execution-tx ["demo/y"] :cancelled)) [])))

(deftest cancel-kills-and-blocks-dependents
  (let [db (db-of diamond :extra (concat (merged "a") (run "b" 1 :running) (run "c" 1 :running)
                                         (task/execution-tx ["demo/b"] :cancelled)))]
    (is (= [{:op :kill :run "demo/b#1" :reason :cancelled}] (actions-for db)))
    (is (= #{"demo/b" "demo/c"} (r/blocked-by (r/index db) "demo/d")))
    ;; killed: nothing restarts it, and d stays blocked once c merges
    (let [db (d/db-with db (concat (task/status-tx db "demo/b#1" :running :killed {})
                                   (task/status-tx db "demo/c#1" :running :merged {})))]
      (is (= [] (actions-for db)))
      (is (= #{"demo/b"} (r/blocked-by (r/index db) "demo/d"))))))

(deftest held-kills-without-restart
  (let [db (db-of [(decl "x")] :extra (concat (run "x" 1 :running) (task/execution-tx ["demo/x"] :held)))]
    (is (= [{:op :kill :run "demo/x#1" :reason :held}] (actions-for db)))))

(deftest environment-change-restarts-clean
  (let [db (db-of [(decl "x" :model "m1")] :extra (concat (run "x" 1 :running :model "m1")
                                                          (task/env-tx "demo/x" {:model "m2"})))]
    (is (= [{:op :kill :run "demo/x#1" :reason :env-changed}] (actions-for db)))
    (let [db (d/db-with db (task/status-tx db "demo/x#1" :running :killed {}))]
      (is (= [(spawn "x" 2 :model "m2")] (actions-for db))))))

(deftest a-previous-roots-runs-are-reaped-and-resumed
  (let [db (db-of [(decl "x") (decl "y" :needs ["x"])] :extra (run "x" 1 :running :boot "boot-0"))]
    (is (= [{:op :reap :run "demo/x#1"} (spawn "x" 2)] (actions-for db)))
    (is (= #{} (:live (r/index db))))))

(deftest ended-runs-are-judged-by-the-done-rule
  (are [extra expected] (= expected (actions-for (db-of diamond :extra extra)))
    ;; green: a work task is accepted on its branch
    (run "a" 1 :ended :outcome :green :results {(t "a") :pass (t "b") :fail})
    [{:op :accept :run "demo/a#1" :task "demo/a"}]
    ;; its proof red: the agent is told (a verdict, which wakes it)
    (run "a" 1 :ended :outcome :red :results {(t "a") :fail})
    [{:op :verdict :run "demo/a#1" :task "demo/a" :reasons [{:reason :proof-red :symbol 'demo-test/a-test}]}]
    ;; out of turns with its proof red: it parks (written :parked, released), never held (design agent-loop section 1)
    (run "a" 1 :ended :outcome :cap :results {(t "a") :fail})
    [{:op :park :run "demo/a#1"}]
    ;; a provider's error with its proof red: held with its cause, never parked
    (run "a" 1 :ended :outcome :error :results {(t "a") :fail})
    [{:op :hold :run "demo/a#1" :task "demo/a" :problem "run demo/a#1 ended error: proof-red demo-test/a-test"}]
    ;; its proof never ran: unknown, which refuses done as red does
    (run "a" 1 :ended :outcome :green :results {})
    [{:op :verdict :run "demo/a#1" :task "demo/a" :reasons [{:reason :proof-unknown :symbol 'demo-test/a-test}]}]
    ;; b needs a, so b carries a's proof (accumulated along :needs): red there is b's own red
    (concat (merged "a") (run "b" 1 :ended :outcome :green :results {(t "a") :fail (t "b") :pass}))
    [{:op :verdict :run "demo/b#1" :task "demo/b" :reasons [{:reason :proof-red :symbol 'demo-test/a-test}]}
     {:op :spawn :task "demo/c" :run "demo/c#1"
      :env {:branch :task-demo-c :model nil :max-turns (cfg/value :seon.config.agent/max-turns)}}]
    ;; a stray red: a red test that is no task's proof
    (run "a" 1 :ended :outcome :green :results {(t "a") :pass 'demo-test/other :error})
    [{:op :verdict :run "demo/a#1" :task "demo/a" :reasons [{:reason :stray-red :symbol 'demo-test/other}]}]
    ;; its agent died
    (run "a" 1 :dead)
    [{:op :hold :run "demo/a#1" :task "demo/a" :problem "its agent died"}]))

(deftest a-run-with-no-end-records-no-fake-spend
  ;; task seon.model/budgets-are-data-and-spend-is-measured: the agent wrote no end (it died
  ;; or was killed before end!), so its turns, tokens and time are unknown: absent, never 0
  (let [db (db-of diamond :extra (run "a" 1 :running))
        tx (#'r/judge-tx {:db db :boot "boot-1" :rid 0 :flights {} :pending {}} {:act {:run "demo/a#1" :task "demo/a"}} {} {:db db :base db :ours [] :uncontracted [] :unmerged []})
        ended (first (filter #(and (map? %) (= "demo/a#1" (:seon.run/id %)) (:seon.run/outcome %)) tx))]
    (is (= :no-end (:seon.run/outcome ended)))
    (is (= #{} (set (filter #{:seon.run/turns :seon.run/model-calls :seon.run/tokens :seon.run/ms} (keys ended)))))))

(defn- fn-tx
  "A recorded definition of fn app.gaps/nm (as seon.program/record-tx writes it), with its
  contract c or none."
  [db nm c]
  (program/record-tx db {:id (str "app.gaps/" nm (when c "-contracted")) :src (str "(defn " nm (when c (str " {:malli/schema " c "}")) " [x] x)")
                         :ns 'app.gaps :ns-form? false :defs [(cond-> {:var (symbol "app.gaps" nm) :kind :fn} c (assoc :contract c))]
                         :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}}))

(deftest the-judge-and-the-merge-gate-agree-on-contract-gaps
  ;; one question, asked by the agent's done check (seon.turn), the writer's diff that root's
  ;; judge reads, and the merge gate: which functions the BRANCH adds without a contract. A
  ;; branch behind a main that fixed its own gap after the fork still holds main's old
  ;; uncontracted g; that gap is main's, not the branch's (the judge used to refuse it while
  ;; the gate took the branch). With contracts off (seon.contract/on? false, a process
  ;; setting) all three answer none: they ask the one function, seon.merge/uncontracted.
  (ts/test-branch!
   (fn [cfg conn]
     (let [c "[:=> [:cat :int] :int]"
           _ (d/transact conn (fn-tx @conn "g" nil))
           ab (pdb/on-main (:branch cfg) :gaps)
           _ (d/branch! conn (:branch cfg) ab)
           base @conn
           _ (d/transact conn (fn-tx @conn "g" c))
           bc (d/connect (assoc cfg :branch ab))]
       (try
         (d/transact bc (fn-tx @bc "u" nil))
         (let [w (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer (a/chan 4) :events (a/chan 4)})
               ask (fn [req] (:r (first (:to-api (second (#'writer/writer-proc w :in (assoc req :dest {:rid 1})))))))
               diff (ask {:op :branch-diff :branch ab})
               gate (first (:results (ask {:op :octopus-merge :branches [ab]})))
               judged (first (filter #(and (map? %) (:seon.run/outcome %))
                                     (#'r/judge-tx {:db @conn :boot "boot-1" :rid 0 :flights {} :pending {}} {:act {:run "demo/a#1" :task "demo/a"}} {}
                                                   diff)))]
           (is (= #{'app.gaps/u} (#'turn/added-gaps base @bc)) "the agent's done check")
           (is (= '[app.gaps/u] (:uncontracted diff)) "the writer's diff")
           (is (= '#{app.gaps/u} (set (:seon.run/uncontracted judged))) "root's judge: g is main's gap, fixed on main")
           (is (= :seon.merge/behind (:seon.error/type gate)) "the gate: behind first, its gaps judged once main merges in"))
         (finally (d/release bc)))))))

(deftest a-run-without-an-end-reads-nil
  ;; no end written (a silent death) is nothing to read, not an empty end
  (is (nil? (r/pull-end (db-of diamond :extra (run "a" 1 :running)) "demo/a#1"))))

(deftest a-death-names-its-cause
  ;; task seon.agent/a-death-names-its-cause: a dead agent's run carries the end it wrote
  ;; on its branch before it exited (its cause), beside the death root observed (exit status);
  ;; a silent death (no end) is named by its exit; a turn loop that threw (the agent's REPL
  ;; answered the error, the process lives) is named by that error, not "wrote no end"
  (let [died (contract/error :seon.agent/died 'seon.root/api-proc "agent a1 died: exit 70" {:agent :a1 :exit {:exit 70}})
        cause (contract/error :seon.agent/eval-error 'seon.repl/serve! "boom" {:class "clojure.lang.ExceptionInfo"})
        row (fn [tx] (first (filter #(and (map? %) (:seon.run/reason %)) tx)))
        err (fn [tx] (:seon.run/error (row tx)))]
    (testing "an end written before the death: its reason and cause, and the exit"
      (let [tx (#'r/dead-tx "demo/a#1" died {:outcome :error :reason "the agent's process died: boom" :error cause})]
        (is (= "the agent's process died: boom (agent a1 died: exit 70)" (:seon.run/reason (row tx))))
        (is (= "boom" (get-in (err tx) [:cause :seon.error/message])))
        (is (= {:exit 70} (:exit (err tx))))))
    (testing "a silent death: named by its exit"
      (let [tx (#'r/dead-tx "demo/a#1" (assoc died :exit {:signal 9} :seon.error/message "agent a1 died: signal 9") nil)]
        (is (= "agent a1 died: signal 9" (:seon.run/reason (row tx))))
        (is (= {:signal 9} (:exit (err tx))))))
    (testing "the turn loop threw and the agent lives: its error is the run's reason"
      (let [db (db-of diamond :extra (run "a" 1 :running))
            tx (#'r/judge-tx {:db db :boot "boot-1" :rid 0 :flights {} :pending {}}
                             {:act {:run "demo/a#1" :task "demo/a"} :worked-error cause} {} {:db db :base db :ours [] :uncontracted [] :unmerged []})]
        (is (= "seon.turn/work! threw: boom" (:seon.run/reason (row tx))))
        (is (= "boom" (:seon.error/message (err tx))))))))

(deftest an-agent-dead-in-its-turn-loop-records-its-cause
  ;; root answers the turn loop's eval with the death itself (the one error map, no eval
  ;; item): the flight reads the branch's end next and keeps the death, exit and stderr,
  ;; rather than asking the dead agent for tests and recording "no live agent"
  (let [died (contract/error :seon.agent/died 'seon.root/api-proc "agent a1 died: exit 70" {:agent :a1 :exit {:exit 70}})
        st {:db (db-of [(decl "a")]) :boot "boot-1" :rid 0 :pending {}
            :flights {"demo/a#1" {:act {:run "demo/a#1" :task "demo/a" :env {:branch :demo/a}} :agent :a1 :step :worked}}}
        [st' outs] (#'r/advance st "demo/a#1" :worked died)]
    (is (= died (get-in st' [:flights "demo/a#1" :died])) "the death is the flight's cause")
    (is (= [{:op :write :req {:op :branch-db :branch :demo/a}}] (mapv :req outs)) "its end is read from the branch next")))

(deftest a-refused-end-is-a-verdict-that-wakes-and-the-same-one-again-holds
  ;; design one-derivation 4.1 section 5 and root's ruling (2026-10-26): a work run's end the done
  ;; rule refuses is words to the agent, a :verdict entry with the reasons and the facts root
  ;; judged on, which wakes its session; the same reasons again hold the task for root (told
  ;; the same twice is no news); different reasons wake it again. A merge refused by the done rule holds
  (let [rs (fn [db aid] (:reasons (first (filter #(= :verdict (:op %)) (actions-for db)))))
        start (fn [db act] (second (#'r/start {:db db :boot "boot-1" :rid 0 :flights {} :pending {}} act)))
        tx-of (fn [outs] (get-in (first outs) [:req :req :tx-data]))
        ended (fn [n] (run "a" n :ended :results {(t "a") :fail} :outcome :red))
        db1 (db-of [(decl "a")] :extra (ended 1))
        reasons (rs db1 "demo/a#1")]
    (testing "the judge: a refused :red or :green end of a work run is a verdict with the done rule's reasons"
      (is (= [{:op :verdict :run "demo/a#1" :task "demo/a" :reasons reasons}] (filterv #(= :verdict (:op %)) (actions-for db1))))
      (is (= [{:reason :proof-red :symbol (t "a")}] reasons)))
    (testing "the first: the run parks with a :verdict entry, synced, carrying the reasons and what root judged on"
      (let [outs (start db1 {:op :verdict :run "demo/a#1" :task "demo/a" :reasons reasons})
            tx (tx-of outs)
            entry (first (filter :seon.message/kind tx))]
        (is (true? (get-in (first outs) [:req :req :flush?])) "synced before the spawn that reads it")
        (is (some #(= [:db/cas [:seon.run/id "demo/a#1"] :seon.run/status :ended :parked] %) tx))
        (is (= [:verdict "demo/a#1"] [(:seon.message/kind entry) (:seon.message/to entry)]))
        (is (= {:reasons reasons :results {(t "a") :fail}} (select-keys (sedn/decode (:seon.message/body entry)) [:reasons :results])))))
    (let [verdict (fn [db n why] (d/db-with db (task/message-tx (str "v-demo/a#" n) :verdict "root" (str "demo/a#" n) (sedn/encode {:reasons why}))))
          db2 (-> (db-of [(decl "a")] :extra (concat (run "a" 1 :parked :outcome :red) (ended 2))) (verdict 1 reasons))]
      (testing "parked by a verdict, its process gone: the next run is a spawn, never a wake"
        (is (= [(spawn "a" 2)] (actions-for (-> (db-of [(decl "a")] :extra (concat (run "a" 1 :parked :outcome :red) [{:seon.run/id "demo/a#1" :seon.run/taken 0}]))
                                                (verdict 1 reasons))))))
      (testing "the same reasons again: held for root, saying so"
        (let [tx (tx-of (start db2 {:op :verdict :run "demo/a#2" :task "demo/a" :reasons reasons}))]
          (is (some #(= [:db/cas [:seon.task/id "demo/a"] :seon.task/execution :running :held] %) tx))
          (is (str/starts-with? (get-in (some :seon.task/failure tx) [:reason]) "the same verdict again: "))
          (is (not-any? :seon.message/kind tx) "and no entry")))
      (testing "different reasons: a verdict again, which wakes"
        (let [tx (tx-of (start db2 {:op :verdict :run "demo/a#2" :task "demo/a" :reasons [{:reason :no-proofs}]}))]
          (is (= :verdict (:seon.message/kind (first (filter :seon.message/kind tx))))))))
    (testing "a merge refused by the done rule holds"
      (is (= [:hold] (mapv :op (actions-for (db-of [(assoc (decl "a") :seon.task/kind :merge)] :extra (ended 1)))))))))

(deftest a-wake-whose-parked-process-is-gone-is-lost-and-the-next-run-a-spawn
  ;; flow-lab's hazard 6 (design agent-loop section 4): the parked process died before the wake's
  ;; work! reached it, so root answers that agent ended; the run took nothing, so it is :lost, not
  ;; a death, and the session's next run is a spawn, a load of the session, never a wake of a
  ;; process that is gone
  (let [ended (contract/error :seon.root/ended 'seon.root/api-proc "agent a-a1 ended" {:agent :a-a1})
        flight {:act {:op :wake :run "demo/a#2" :task "demo/a" :env {:branch :demo/a}} :agent :a-a1 :step :worked}
        st {:db (db-of [(decl "a")]) :boot "boot-1" :rid 0 :pending {} :flights {"demo/a#2" flight}}
        [_ outs] (#'r/advance st "demo/a#2" :worked ended)
        tx (get-in (first outs) [:req :req :tx-data])]
    (is (some #(= [:db/cas [:seon.run/id "demo/a#2"] :seon.run/status :running :lost] %) tx) "the wake's run is lost")
    (is (some #(some-> (:seon.run/reason %) (str/includes? "agent a-a1 ended")) tx) "with why")
    (is (= [(spawn "a" 3)]
           (actions-for (db-of [(decl "a")] :extra (concat (run "a" 1 :parked :outcome :waiting) [{:seon.run/id "demo/a#1" :seon.run/taken 0}]
                                                           (run "a" 2 :lost)
                                                           (task/message-tx "m1" :message "user" "demo/a#1" "carry on")))))
        "the next run is a spawn")))

(deftest the-judge-reads-its-branch-in-one-request
  ;; task seon.reconcile/the-judge-reads-its-branch-in-one-request: once the tests answer, the flight
  ;; asks the writer once (:branch-diff, whose answer holds the head, its merge base and what it
  ;; changed), never :branch-db then :branch-diff; that one answer is what the run is judged on
  (let [db (db-of [(decl "a")] :extra (run "a" 1 :running))
        flight {:act {:run "demo/a#1" :task "demo/a" :env {:branch :demo/a}} :agent :a1}
        st {:db db :boot "boot-1" :rid 0 :pending {} :flights {"demo/a#1" (assoc flight :step :tested)}}
        [st' outs] (#'r/advance st "demo/a#1" :tested {:reports []})]
    (is (= [{:op :write :req {:op :branch-diff :branch :demo/a}}] (mapv :req outs)) "one request, the diff")
    (is (= :diffed (get-in st' [:pending (:tag (first outs)) 1])) "answered at :diffed, with no :read step between")
    (let [st (assoc-in st [:flights "demo/a#1"] (assoc flight :step :diffed :results {}))
          [_ outs] (#'r/advance st "demo/a#1" :diffed {:ok true :db db :base db :base-t (:max-tx db) :ours [] :theirs 0 :conflicts 0
                                                        :unmerged [] :uncontracted [] :caused [] :vars #{}})]
      (is (= {:op :kill :id :a1 :ended true} (:req (first outs))) "its agent is stopped")
      (is (some #(= "demo/a#1" (:seon.run/id %)) (get-in (second outs) [:req :req :tx-data])) "and its end judged from that one answer"))))

(deftest a-refused-diff-ends-the-run-with-its-cause
  ;; the writer refuses to diff the branch: the run is not judged on facts nobody
  ;; measured (no changed keys, no gaps); its agent is stopped and the run ends with the
  ;; reason as its error, the read not asked again
  (let [no (contract/error :seon.writer/refused 'seon.writer/writer-proc "no branch :demo/a" {})
        st {:db (db-of [(decl "a")]) :boot "boot-1" :rid 0 :pending {}
            :flights {"demo/a#1" {:act {:run "demo/a#1" :task "demo/a" :env {:branch :demo/a}} :agent :a1 :step :diffed}}}
        [st' outs] (#'r/advance st "demo/a#1" :diffed no)]
    (is (= {:op :kill :id :a1 :ended true} (:req (first outs))))
    (is (= [[:db/cas [:seon.run/id "demo/a#1"] :seon.run/status :running :ended]
            {:seon.run/id "demo/a#1" :seon.run/reason "no branch :demo/a" :seon.run/error no}]
           (update (get-in (second outs) [:req :req :tx-data]) 1 dissoc :seon.run/end-ms)))
    (is (= :done (get-in st' [:pending (:tag (second outs)) 1])) "its flight ends with the write")))

(deftest an-ended-run-of-a-cancelled-task-is-closed
  (is (= [{:op :close :run "demo/a#1"}]
         (actions-for (db-of diamond :extra (concat (run "a" 1 :ended :outcome :green :results {(t "a") :pass})
                                                    (task/execution-tx ["demo/a"] :cancelled)))))))

(deftest done-explain-table
  (are [in expected] (= expected (mapv (juxt :reason :symbol) (r/done-explain in)))
    {:proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{}} []
    {:proofs #{} :results {} :main-green #{} :allowed-red #{} :uncontracted #{}} [[:no-proofs nil]]
    {:proofs #{'p/a} :results {'p/a :error} :main-green #{} :allowed-red #{} :uncontracted #{}} [[:proof-red 'p/a]]
    ;; a proof that never ran is unknown, not red
    {:proofs #{'p/a} :results {} :main-green #{} :allowed-red #{} :uncontracted #{}} [[:proof-unknown 'p/a]]
    {:proofs #{'p/a} :results {'p/a :pass 'p/m :fail} :main-green #{'p/m} :allowed-red #{} :uncontracted #{}} [[:regression 'p/m]]
    {:proofs #{'p/a} :results {'p/a :pass 'p/o :fail} :main-green #{} :allowed-red #{'p/o} :uncontracted #{}} []
    {:proofs #{'p/a} :results {'p/a :pass 'p/s :fail} :main-green #{} :allowed-red #{} :uncontracted #{}} [[:stray-red 'p/s]]
    {:proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{'app/f}} [[:contract-gap 'app/f]]
    {:proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{} :unmerged ["[:var app/f]"]} [[:unmerged nil]]))

(deftest the-writer-refuses-cycles-and-unknown-needs
  (let [db (empty-db)]
    (is (= :seon.task/refused (:seon.error/type (task/create-tx db [(decl "a" :needs ["b"]) (decl "b" :needs ["a"])]))))
    (is (= :seon.task/refused (:seon.error/type (task/create-tx db [(decl "a" :needs ["nope"])]))))
    (is (vector? (task/create-tx db diamond)))))

(deftest regression-is-judged-against-main-at-the-runs-basis
  ;; x merged at main t 100; y (independent of x) was observed against main at t 50,
  ;; before x's merge, so x's red proof there is not a regression of the main y holds
  (let [two [(decl "x") (decl "y")]
        merged-x (into (run "x" 1 :merged) [{:seon.run/id "demo/x#1" :seon.run/merged-t 100}])
        y (fn [basis] (into (run "y" 1 :ended :outcome :green :results {(t "x") :fail (t "y") :pass})
                            [{:seon.run/id "demo/y#1" :seon.run/basis-t basis}]))]
    (is (= {:op :accept :run "demo/y#1" :task "demo/y"}
           (first (actions-for (db-of two :extra (concat merged-x (y 50)))))))
    (is (= :verdict (:op (first (actions-for (db-of two :extra (concat merged-x (y 150))))))) "refused: told the agent")))

(deftest contracts-are-checked
  ;; with the contracts on (:seon.config.contract/instrument, config: in every digest), a call
  ;; outside its contract is refused at entry with the one error map. (It read an inherited
  ;; PROTO_INSTRUMENT, an input no digest held; pure-tests 2026-10-23.)
  (when contract/on?
    (is (= :seon.instrument/contract-error
           (:seon.error/type (try (task/task-id "not-a-symbol" "x") (catch Exception e (ex-data e))))))))

;; ---- true branches: merge tasks, unmerged entries, behind, a refused run held for root ----

(def conflict-f {:key [:var 'app/f] :kind :both-modified :base {:src "(defn f [x] x)"}
                 :ours {:src "(defn f [x] (inc x))"} :theirs {:src "(defn f [x] (dec x))"}})

(defn- mdb
  "A main db of decls with their merge tasks (template/with-merge), all released."
  [decls & {:keys [extra max-running] :or {max-running 4}}]
  (let [db (empty-db)
        ds (tpl/with-merge decls)
        db (d/db-with db (task/create-tx db ds))
        db (-> db (d/db-with (task/execution-tx (mapv #(task/task-id 'demo (:seon.task/name %)) ds) :running))
               (d/db-with (task/boot-tx "boot-1" max-running)))]
    (cond-> db (seq extra) (d/db-with extra))))

(defn- mspawn [nm n] {:op :spawn :task (str "demo/" nm) :run (str "demo/" nm "#" n)
                      :env {:branch (task/default-branch (str "demo/" (str/replace nm #"-merge$" ""))) :model nil :max-turns (cfg/value :seon.config.agent/max-turns)}})

(deftest plan-and-proofs-accumulate-along-needs
  (let [db (mdb [(decl "a") (decl "b" :needs ["a"])])
        acc #(:seon.task/accumulated (task/task db %))]
    (testing "a merge task inherits its work task's plan and proofs"
      (is (= #{(t "a")} (:proofs (acc "demo/a-merge"))))
      (is (str/includes? (:plan (acc "demo/a-merge")) "Make a true.")))
    (testing "b, after a's merge, carries a's proofs too: a refinement only tightens"
      (is (= #{(t "a") (t "b")} (:proofs (acc "demo/b"))))
      (is (= #{(t "a") (t "b")} (:proofs (acc "demo/b-merge")))))
    (testing "the done rule judges every accumulated proof"
      (is (= [[:proof-red (t "a")]]
             (mapv (juxt :reason :symbol)
                   (r/done-explain (r/done-input (r/index db) "demo/b-merge" {(t "b") :pass (t "a") :fail} #{} [] nil nil #{}))))))))

(deftest an-index-observed-from-the-last-is-the-index-listed
  (let [db0 (db-of diamond)
        steps [["a plan improved" [{:seon.task/id "demo/b" :seon.task/plan "Make b truer."}]]
               ["a run started and ended" (run "a" 1 :ended :results {(t "a") :pass})]
               ["a run merged" [{:seon.run/id "demo/a#1" :seon.run/status :merged :seon.run/merged-t 7}]]
               ["a need added" [{:seon.task/id "demo/c" :seon.task/needs #{"demo/a" "demo/b"}}]]
               ["a proof added" [{:seon.task/id "demo/a" :seon.task/proofs #{(t "a") 'demo-test/more}}]]
               ["a new boot" (task/boot-tx "boot-2" 4)]]]
    (reduce (fn [[db idx] [what tx]]
              (let [db' (d/db-with db tx) idx' (r/index idx db db')]
                (testing what (is (= (r/index db') idx')))
                [db' idx']))
            [db0 (r/index db0)] steps)))

(defn observed-input
  "The cost proof's input at n: the index of a main db with n unrelated tasks, that db, and
  the db after one task's plan improved (one datom differs)."
  [n]
  (let [db0 (db-of (mapv #(decl (str "u" %)) (range n)))]
    [(r/index db0) db0 (d/db-with db0 [{:seon.task/id "demo/u0" :seon.task/plan "Make u0 truer."}])]))

(deftest an-index-is-observed-at-the-cost-of-what-changed
  (cost/proof {:of 'seon.reconcile/index :input 'seon.reconcile-test/observed-input
               :n "unrelated tasks on main, beside one whose plan changed"
               :work (str "a datahike.api/datoms-diff of each of the 74 watched attributes' ranges, one pull of the "
                          "entity that differs and its task re-derived. The test store keeps no node at an address, so "
                          "each diff walks the attribute's datoms (linear here; on a stored db it skips the subtrees "
                          "both share: 1.29M ticks, 10.2 MB on a root's main of 680 tasks and 118k datoms). Listing "
                          "the index again, as seon.turn/observe did every turn, settles all n tasks: n^1.75, 258M "
                          "ticks and 922 MB at 1,000 (92.0M ticks, 384 MB on that root's main)")
               :growth :linear
               ;; measured 2026-10-24: 8.51M ticks / 67.7 MB at 10, 8.23M / 61.1 MB at 100, 11.3M / 68.7 MB at 1,000
               :fixed {:ticks 8500000.0 :bytes 65000000.0}
               :per-n {:ticks 3000.0 :bytes 8000.0}
               :sizes [10 100 1000]}))

(deftest with-merge-chains-work-through-merges
  (let [db (mdb [(decl "a") (decl "b" :needs ["a"])])]
    (is (= {:seon.task/kind :merge :seon.task/needs #{"demo/a"} :seon.task/branch :task-demo-a}
           (select-keys (task/task db "demo/a-merge") [:seon.task/kind :seon.task/needs :seon.task/branch])))
    (is (= #{"demo/a-merge"} (:seon.task/needs (task/task db "demo/b"))))
    (testing "a's work first; accepted on its branch, its merge task starts on the same branch"
      (is (= [(mspawn "a" 1)] (actions-for db)))
      (let [db (d/db-with db (run "a" 1 :done))]
        (is (= [(mspawn "a-merge" 1)] (actions-for db)))
        (testing "b waits for a's merge, not a's work"
          (is (= #{"demo/a-merge"} (r/blocked-by (r/index db) "demo/b"))))))))

(deftest green-merge-run-merges
  ;; an octopus merge of one: no other merge run runs
  (is (= [{:op :octopus-merge :run "demo/a-merge#1" :members [{:run "demo/a-merge#1" :task "demo/a-merge"}]}]
         (actions-for (mdb [(decl "a")] :extra (concat (run "a" 1 :done)
                                                       (run "a-merge" 1 :ended :outcome :green :results {(t "a") :pass}))))))
  ;; (the run helper names branches by task id; only the action matters here)
  )

(deftest a-merge-with-deferred-tests-is-gated-only-when-root-asks
  ;; root's rulings (2026-10-22/23) and the owner's (2026-10-23: process tests never run
  ;; automatically): with root's go on the task (my.root/gate!, :seon.task/gate?) the process
  ;; tests a branch's change reaches run before its merge, on a worktree of the root's declared
  ;; gate checkout, and gate it like proofs: unrun, the member is gated first (alone); all
  ;; passing, it merges; a red, or a run that could not run them (:error), is refused as a red
  ;; proof is, never hidden. Unasked, or with no gate checkout declared, it merges with them
  ;; unrun (an open problem until they run)
  (let [gate {:seon.root/id :root :seon.config.source/gate-checkout "/the/merges/tree"}
        asked {:seon.task/id "demo/a-merge" :seon.task/gate? true}
        judged (fn [root go & kv] (actions-for (mdb [(decl "a")] :extra (concat (run "a" 1 :done)
                                                                             (apply run "a-merge" 1 :ended :outcome :green :results {(t "a") :pass}
                                                                                    :deferred ['seon.root-test/boots] kv)
                                                                             (when go [go])
                                                                             (when root [root])))))
        octopus [{:op :octopus-merge :run "demo/a-merge#1" :members [{:run "demo/a-merge#1" :task "demo/a-merge"}]}]]
    (is (= [{:op :gate :run "demo/a-merge#1" :task "demo/a-merge" :tests ['seon.root-test/boots]}] (judged gate asked)))
    (is (= octopus (judged gate asked :deferred-results {'seon.root-test/boots :pass})))
    (is (= [:hold] (map :op (judged gate asked :deferred-results {'seon.root-test/boots :fail}))))
    (is (= [:hold] (map :op (judged gate asked :deferred-results {'seon.root-test/boots :error}))) "a run that could not run them is no pass")
    (is (str/includes? (:problem (first (judged gate asked :deferred-results {'seon.root-test/boots :fail}))) "seon.root-test/boots"))
    (is (= octopus (judged gate nil)) "unasked: it merges, the deferred tests unrun")
    (is (= octopus (judged nil asked)) "no gate checkout declared: it merges, the deferred tests unrun")))

(deftest a-deferred-proof-is-neither-red-nor-unknown
  ;; owner's ruling (2026-10-23): a process test never runs in an agent's turn or root's judge,
  ;; a proof among them; the done rule reads it as deferred to root's gate, and still refuses a
  ;; proof that is neither green nor deferred
  (let [in {:proofs #{'app/p 'app/q} :results {'app/p :pass} :main-green #{} :allowed-red #{} :uncontracted #{}}]
    (is (= [{:reason :proof-unknown :symbol 'app/q}] (r/done-explain in)))
    (is (= [] (r/done-explain (assoc in :deferred #{'app/q}))))
    (is (= [{:reason :proof-red :symbol 'app/p}] (r/done-explain (assoc in :results {'app/p :fail} :deferred #{'app/q}))))))

(deftest merges-run-one-at-a-time
  ;; git's merge queue: a merge agent's last merge-main and root's merge see the same main,
  ;; so a merge is never behind; work tasks still run in parallel
  (let [three [(decl "x") (decl "y") (decl "z")]
        done-work (mapcat #(run % 1 :done) ["x" "y" "z"])]
    (are [extra expected] (= expected (map :task (filter #(= :spawn (:op %)) (actions-for (mdb three :extra extra)))))
      done-work ["demo/x-merge"]
      ;; one merge running: none starts
      (concat done-work (run "x-merge" 1 :running)) []
      ;; one ended, awaiting root's merge: still the queue's
      (concat done-work (run "x-merge" 1 :ended :outcome :green :results {(t "x") :pass})) []
      ;; merged: the next one starts
      (concat done-work (run "x-merge" 1 :merged)) ["demo/y-merge"])
    (testing "work tasks are not queued"
      (is (= ["demo/x" "demo/y" "demo/z"] (map :task (actions-for (mdb three))))))))

(deftest boot-records-merge-max-running-one
  ;; DECISIONS: octopus merges stay off (one merge at a time) until the octopus merge's combined test run
  ;; and the par-200 hang are fixed; the config value in force is a fact on main a person can read
  (let [db (d/db-with (empty-db) (into (task/boot-tx "boot-1" 4) (cfg/facts cfg/config {})))]
    (is (= 1 (cfg/value :seon.config.merge/max-running)))
    (is (= {:boot "boot-1" :max-running 4 :max-merging 1} (task/root db)) "both config keys are recorded at boot")))

(deftest two-ready-disjoint-merges-run-one-at-a-time-by-default
  ;; x and y change disjoint definitions: an octopus merge could take both, but the config says 1
  (let [two [(decl "x") (decl "y")]
        extra (concat (run "x" 1 :done :changed #{"[:var app.m/x]"}) (run "y" 1 :done :changed #{"[:var app.m/y]"}))]
    (is (= ["demo/x-merge"] (map :task (filter #(= :spawn (:op %)) (actions-for (mdb two :extra extra))))))
    (testing "an octopus merge is turned on by writing the fact"
      (is (= ["demo/x-merge" "demo/y-merge"]
             (map :task (filter #(= :spawn (:op %)) (actions-for (mdb two :extra (concat extra [{:seon.root/id :root :seon.config.merge/max-running 8}]))))))))))

(deftest unmerged-refuses-done
  (is (= [{:op :verdict :run "demo/a#1" :task "demo/a" :reasons [{:reason :unmerged :key "[:var app/f]"}]}]
         (actions-for (db-of diamond :extra (run "a" 1 :ended :outcome :green :results {(t "a") :pass}
                                                     :unmerged ["[:var app/f]"]))))))

(deftest an-open-problem-its-work-caused-refuses-done
  ;; notes/health-design-2026-10-23.md: recorded at its end (seon.problems/caused), refused by
  ;; the done rule, so the agent is told naming it (a :verdict); its evaluations were never refused
  (let [msg "docs/x.md#y cites app/f, which names nothing"]
    (is (= [{:op :verdict :run "demo/a#1" :task "demo/a" :reasons [{:reason :open-problem :key msg}]}]
           (actions-for (db-of diamond :extra (run "a" 1 :ended :outcome :green :results {(t "a") :pass} :caused [msg])))))
    (is (= [{:reason :open-problem :key msg}]
           (r/done-explain {:proofs #{(t "a")} :results {(t "a") :pass} :main-green #{} :allowed-red #{} :uncontracted #{} :caused #{msg}})))))

(deftest behind-merge-gets-a-new-run
  (testing "root's merge found main moved: the merge task's next run is a new agent; no catch-up"
    (let [db (mdb [(decl "a")] :extra (concat (run "a" 1 :done) (run "a-merge" 1 :behind :outcome :green)))]
      (is (= [(mspawn "a-merge" 2)] (actions-for db))))))

(deftest design-may-leave-its-proofs-red
  ;; a design step's work is the plan: done only when the plan differs from the one it began with
  (are [in expected] (= expected (mapv :reason (r/done-explain in)))
    {:kind :design :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{} :plan "B" :plan-before "A"} []
    {:kind :design :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{'app/f} :plan "B" :plan-before "A"} [:contract-gap]
    {:kind :design :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{} :plan "A" :plan-before "A"} [:no-design]
    ;; red is a design's to leave; a proof with no result was never written (or never ran):
    ;; the step E dry run's first real design ended at its plan with its proof undefined, and
    ;; implement spent 25 turns looking for it
    {:kind :design :proofs #{'p/a} :results {} :main-green #{} :allowed-red #{} :uncontracted #{} :plan "B" :plan-before "A"} [:proof-unknown]
    ;; a design that only rewrote its plan is not done: live agents, 2026-10-25, a design ended
    ;; green at its first plan edit, the proofs its plan named never written, and its implement
    ;; started with none (notes/live-2026-10-25.md D3)
    {:kind :design :proofs #{} :results {} :main-green #{} :allowed-red #{} :uncontracted #{} :plan "B" :plan-before "A"} [:no-proofs]
    {:kind :work :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{}} [:proof-red]))

(defn- design-db
  "Main with design task demo/a released, its run #1 ended as status, extra after."
  [extra]
  (let [db (empty-db)
        d [(assoc (decl "a") :seon.task/kind :design)]
        db (d/db-with db (task/create-tx db d))]
    (-> db (d/db-with (task/execution-tx ["demo/a"] :running)) (d/db-with (task/boot-tx "boot-1" 4)) (d/db-with extra))))

(deftest a-design-step-is-not-done-at-its-start
  ;; measured 2026-10-21: a design run ended :done :green with 0 turns; its proofs may stay
  ;; red, so at its start nothing refused it
  (testing "at its start (the plan as it began), the done rule refuses :no-design"
    (let [db (design-db (run "a" 1 :running))]
      (is (= [:no-design]
             (mapv :reason (r/done-explain (r/done-input (r/index db) "demo/a" {(t "a") :fail} #{} []
                                                           nil (:seon.run/plan (task/run db "demo/a#1")) #{})))))))
  (testing "ended green with the plan unchanged: refused, so the agent is told why, never accepted"
    (is (= [{:op :verdict :run "demo/a#1" :task "demo/a" :reasons [{:reason :no-design}]}]
           (actions-for (design-db (run "a" 1 :ended :outcome :green :results {(t "a") :fail})))))))

(deftest a-design-step-is-done-when-it-improved-the-plan
  ;; root's judge carried the plan the run improved on its branch to main (judge-tx)
  (is (= [{:op :accept :run "demo/a#1" :task "demo/a"}]
         (actions-for (design-db (concat (run "a" 1 :ended :outcome :green :results {(t "a") :fail})
                                         [{:seon.task/id "demo/a" :seon.task/plan "Make a true. Design: (defn f [xs] ...) one pass over xs."}]))))))

(defn- told
  "db with a :verdict entry to run aid giving reasons, as the judge's first verdict of the session wrote it."
  [db aid reasons] (d/db-with db (task/message-tx (str "v-told-" aid) :verdict "root" aid (sedn/encode {:reasons reasons}))))

(defn- held
  "main db after the hold the reconciler's one action for it writes, and the action (its :problem
  the hold's): a work run's refused end holds when the session was told the same verdict
  already (start), so the verdict is told first; a death or a merge refused by the done rule holds at once."
  [db]
  (let [[act :as acts] (actions-for db)]
    (assert (#{[:hold] [:verdict]} (map :op acts)) (pr-str acts))
    (if (= :hold (:op act))
      [(d/db-with db (r/hold-tx (task/run db (:run act)) (:problem act))) act]
      (let [db (told db (:run act) (:reasons act))
            [_ outs] (#'r/start {:db db :boot "boot-1" :rid 0 :flights {} :pending {}} act)
            tx (get-in (first outs) [:req :req :tx-data])]
        [(d/db-with db tx) (assoc act :problem (:reason (some :seon.task/failure tx)))]))))

(deftest a-refused-run-holds-its-task-and-creates-none
  ;; owner, 2026-10-23: "no automatic retries, send it to root to decide if it should be
  ;; retried", and root, 2026-10-26: a work run's refused end is told to the agent once (a
  ;; :verdict), and the same verdict again holds. Every hold holds its task as my.task/fail!
  ;; does: its failure (the reason, the run), the measured evidence, the run closed; no task is
  ;; created or cancelled
  (doseq [[why extra problem]
          [["a red proof" (run "add" 1 :ended :outcome :red :results {(t "add") :fail})
            "the same verdict again: proof-red demo-test/add-test"]
           ["its proof never run" (run "add" 1 :ended :outcome :red :results {})
            "the same verdict again: proof-unknown demo-test/add-test"]
           ["a dead agent" (run "add" 1 :dead) "its agent died"]
           ["a merge conflict" (concat (run "add" 1 :done)
                                       (run "add-merge" 1 :ended :outcome :red :results {(t "add") :pass}
                                                :conflicts [conflict-f] :unmerged ["[:var app/f]"]))
            "run demo/add-merge#1 ended red: unmerged [:var app/f]"]]]
    (testing why
      (let [db (mdb [(decl "add") (decl "twice" :needs ["add"])] :extra extra)
            [db' act] (held db)
            id (:task act) aid (:run act)
            tk (task/task db' id)]
        (is (= problem (:problem act)))
        (is (= (set (keys (task/tasks db))) (set (keys (task/tasks db')))) "no new task")
        (is (not-any? #(= :cancelled (:seon.task/execution %)) (vals (task/tasks db'))) "none cancelled")
        (is (= :held (:seon.task/execution tk)))
        (is (= {:reason problem :run aid} (:seon.task/failure tk)))
        (is (= aid (:from (:seon.task/evidence tk))))
        (is (= :closed (:seon.run/status (task/run db' aid))) "the run is answered once")
        (is (= :failed (r/task-state (r/index db') id))))))
  (testing "a merge the octopus merge refused: the reconciler proc's answer to the writer"
    (let [db (mdb [(decl "add")] :extra (concat (run "add" 1 :done) (run "add-merge" 1 :ended :outcome :green :results {(t "add") :pass})))
          [st outs] (r/reconciler-proc (r/reconciler-proc {:boot "boot-1"}) :main {:t (:max-tx db) :ops [] :db db :ns (profile/now-ns)})
          asked (first (filter #(= :octopus-merge (get-in % [:req :req :op])) (:to-api outs)))
          reason (contract/error :seon.writer/refused 'seon.writer/octopus-merge "conflicts with main at app/f" {:merged? false})
          [_ outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r {:ok true :results [reason]}})
          tx (vec (mapcat #(get-in % [:req :req :tx-data]) (filter #(= :write (get-in % [:req :op])) (:to-api outs2))))
          db' (d/db-with db tx)
          tk (task/task db' "demo/add-merge")]
      (is (some? asked) "the green merge is asked of the writer")
      (is (= (set (keys (task/tasks db))) (set (keys (task/tasks db')))) "no new task")
      (is (= :held (:seon.task/execution tk)))
      (is (= {:reason "merge refused: conflicts with main at app/f" :run "demo/add-merge#1"} (:seon.task/failure tk)))
      (is (= :closed (:seon.run/status (task/run db' "demo/add-merge#1"))))
      (is (= reason (:seon.run/error (task/run db' "demo/add-merge#1"))) "the writer's reason, on the run"))))

(deftest a-held-task-shows-its-reason-among-the-problems
  (let [[db' act] (held (mdb [(decl "add")] :extra (run "add" 1 :ended :outcome :red :results {(t "add") :fail})))
        [row & more] (get-in (problems/report db') [:classes :failed :rows])]
    (is (nil? more))
    (is (= {:task "demo/add" :run "demo/add#1" :reason (:problem act) :open? true}
           (select-keys row [:task :run :reason :open?])))
    (is (= [(t "add")] (get-in row [:evidence :red])) "with its measured evidence")
    (is (= [] (:answered-by row)) "its own evidence is not an answer to it")))

(deftest roots-release-runs-again-on-the-same-branch-with-the-evidence
  (let [db (mdb [(decl "add")] :extra (run "add" 1 :ended :outcome :red :results {(t "add") :fail}))
        [db' act] (held db)
        released (d/db-with db' (task/execution-tx ["demo/add"] :running))
        [sp & more] (actions-for released)]
    (is (nil? more))
    (is (= (mspawn "add" 2) sp) "a new run, on the task's own branch")
    (is (= (get-in (task/run db "demo/add#1") [:seon.run/env :branch]) (get-in sp [:env :branch])))
    (testing "the resumed branch takes the task as main holds it: its failure and evidence"
      (let [on-branch (d/db-with db (task/onto-tx released db "demo/add"))
            tk (task/task on-branch "demo/add")]
        (is (= (:problem act) (:problem (:seon.task/evidence tk))))
        (is (= [(t "add")] (:red (:seon.task/evidence tk))))
        (is (= "demo/add#1" (:run (:seon.task/failure tk))))))
    (is (not= :failed (r/task-state (r/index released) "demo/add")) "released, it is no longer failed")))

(deftest an-idle-system-does-nothing-after-a-reason
  ;; the reconciler proc's own steps: the reason's hold is one write; once it is committed
  ;; and answered nothing is left to do (quiet, naming the held task as a root cause); a
  ;; root-only commit after it asks nothing
  (let [db (told (mdb [(decl "add") (decl "twice" :needs ["add"])]
                      :extra (run "add" 1 :ended :outcome :red :results {(t "add") :fail}))
                 "demo/add#1" [{:reason :proof-red :symbol (t "add")}])
        commit (fn [db tx] (let [rep (d/with db tx)] [(:db-after rep) (pdb/report->tx rep)]))
        main (fn [st db ops] (r/reconciler-proc st :main {:t (:max-tx db) :ops ops :db db :ns (profile/now-ns)}))
        reqs (fn [outs] (remove #(= :stat (:op %)) (:to-api outs)))
        [st outs] (main (r/reconciler-proc {:boot "boot-1"}) db [])
        [w & others] (reqs outs)
        tx (get-in w [:req :req :tx-data])
        ;; the writer commits, then answers: main's commit reaches the proc first
        [db2 ops2] (commit db tx)
        [st outs2a] (main st db2 ops2)
        [st outs2b] (r/reconciler-proc st :answers {:tag (:tag w) :r {:ok true}})
        outs2 {:to-api (concat (:to-api outs2a) (:to-api outs2b))}
        quiet (some #(get-in % [:req :req :tx-data 0 :seon.root/quiescent]) (reqs outs2))]
    (is (empty? others) "one request: the hold")
    (is (= :held (:seon.task/execution (task/task db2 "demo/add"))))
    (is (= [{:task "demo/add" :cause "the same verdict again: proof-red demo-test/add-test" :run "demo/add#1"
             :blocks ["demo/add-merge" "demo/twice" "demo/twice-merge"]}]
           (:root-causes quiet)) "quiet, naming the held task and what it blocks")
    (is (not-any? #(= :spawn (get-in % [:req :op])) (reqs outs2)) "nothing starts")
    (let [[db3 ops3] (commit db2 [{:seon.root/id :root :seon.root/quiescent quiet}])
          [_ outs3] (main st db3 ops3)]
      (is (empty? (reqs outs3)) "a commit of root's own report asks nothing"))))

(deftest stuck-names-cancelled-and-held-roots
  (let [db (db-of diamond :extra (concat (merged "a") (task/execution-tx ["demo/b"] :cancelled) (task/execution-tx ["demo/c"] :held)))]
    (is (= [{:task "demo/b" :cause "cancelled" :blocks ["demo/d"]} {:task "demo/c" :cause "held" :blocks ["demo/d"]}]
           (r/root-causes (r/index db))))))

(deftest hairy-rule
  (let [t {:seon.task/accumulated {:plan "Use `app/g` and `app/f`." :proofs #{'app.proofs/a-test}}}
        c (fn [k kind] {:key [:var k] :kind kind})
        merge-cfg {:max-conflicts 3 :max-merge-turns 2}]
    (are [conflicts merge-turns rule] (= rule (:rule (r/hairy (merge merge-cfg {:conflicts conflicts :task t :merge-turns merge-turns}))))
      [] 0 nil
      [(c 'app/f :both-modified)] 0 nil
      [(c 'app/f :both-modified) (c 'app/h :both-modified) (c 'app/i :both-added)] 0 nil
      [(c 'app/f :both-modified) (c 'app/h :both-modified) (c 'app/i :both-added) (c 'app/j :both-added)] 0 :too-many-conflicts
      [(c 'app/g :deleted-by-them)] 0 :deleted-by-them-referenced
      [(c 'app/z :deleted-by-them)] 0 nil
      [(c 'app.proofs/a-test :deleted-by-them)] 0 :deleted-by-them-referenced
      [(c 'app/f :both-modified)] 3 :merge-turns)))

(deftest quiescence-is-reported-when-nothing-can-move
  ;; the reconciler proc's own steps over real commits: a task held for root and its dependents, nothing live
  (let [db (mdb [(decl "add") (decl "twice" :needs ["add"])]
                :extra (concat (run "add" 1 :closed)
                               [{:seon.task/id "demo/add" :seon.task/execution :held :seon.task/failure {:reason "capped" :run "demo/add#1"}}]))
        commit (fn [db tx] (let [rep (d/with db tx)] [(:db-after rep) (pdb/report->tx rep)]))
        step (fn [st db ops] (let [[st outs] (r/reconciler-proc st :main {:t (:max-tx db) :ops ops :db db :ns (profile/now-ns)})] [st (:to-api outs)]))
        report-in (fn [outs] (some #(get-in % [:req :req :tx-data 0 :seon.root/quiescent]) outs))
        [st outs] (step (r/reconciler-proc {:boot "boot-1"}) db [])
        report (report-in outs)
        blocked ["demo/add-merge" "demo/twice" "demo/twice-merge"]]
    (testing "nothing to start, nothing in flight: one positive report, naming the held root and what it blocks"
      (is (empty? (:flights st)))
      (is (= {:t (:max-tx db) :root-causes [{:task "demo/add" :cause "capped" :run "demo/add#1" :blocks blocked}]} report))
      (is (some #(= {:op :collect} (get-in % [:req :req])) outs) "and asks the writer to collect the store, once a spell")
      (is (:quiet st)))
    (let [[db2 ops2] (commit db [{:seon.root/id :root :seon.root/quiescent report}])
          [st2 outs2] (step st db2 ops2)]
      (testing "the report's own commit (root only) makes no second report; main holds it"
        (is (:quiet st2))
        (is (nil? (report-in outs2)))
        (is (= report (r/quiescent db2))))
      (testing "a commit touching tasks ends the spell: nothing can move still, so it is reported again at the new t"
        (let [[db3 ops3] (commit db2 [{:seon.task/id "demo/twice" :seon.task/plan "Make twice true, again."}])
              [_ outs3] (step st2 db3 ops3)]
          (is (= (:max-tx db3) (:t (report-in outs3))))))
      (testing "work to start is not quiet: root's release of add starts it, and no report goes out"
        (let [[db4 ops4] (commit db2 (task/execution-tx ["demo/add"] :running))
              [st4 outs4] (step st2 db4 ops4)]
          (is (= ["demo/add"] (keep #(get-in % [:req :opts :task]) outs4)))
          (is (not (:quiet st4)))
          (is (nil? (report-in outs4))))))))

(def ^:private held-why "branch :demo/a is held by live agent a9")

(deftest a-refused-spawn-writes-a-refused-run
  ;; root refuses the spawn (the branch is held, or too many live agents): the refused answer is
  ;; the run, written on main, and the next pass starts the task again
  (let [db (db-of [(decl "a")])
        [st outs] (r/reconciler-proc (r/reconciler-proc {:boot "boot-1"}) :main {:t (:max-tx db) :ops [] :db db :ns (profile/now-ns)})
        asked (first (filter #(= :spawn (get-in % [:req :op])) (:to-api outs)))
        held (contract/error :seon.root/refused 'seon.root/spawn! held-why {:branch :demo/a :agent :a9})
        [_ outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r held})
        written (vec (mapcat #(get-in % [:req :req :tx-data]) (filter #(= :write (get-in % [:req :op])) (:to-api outs2))))
        run (first (filter #(= "demo/a#1" (:seon.run/id %)) written))]
    (is (some? asked) "the first pass asks for the spawn")
    (is (= :refused (:seon.run/status run)) "the refused answer is written as the run")
    (is (= held-why (:seon.run/reason run)) "with root's reason")
    (is (= [(spawn "a" 2)] (actions-for (d/db-with db written))) "the next pass starts the task again")))

(deftest a-refused-task-asks-again-when-main-moves
  ;; if nothing has changed, asking again is refused again: neither the refused answer nor
  ;; its own commit starts the task; other work on main lets it ask again, as its next run
  (let [commit (fn [db tx] (let [rep (d/with db tx)] [(:db-after rep) (pdb/report->tx rep)]))
        main (fn [st db ops] (r/reconciler-proc st :main {:t (:max-tx db) :ops ops :db db :ns (profile/now-ns)}))
        spawns (fn [outs] (vec (keep #(when (= :spawn (get-in % [:req :op])) (get-in % [:req :opts :task])) (:to-api outs))))
        db (db-of [(decl "a")])
        [st outs] (main (r/reconciler-proc {:boot "boot-1"}) db [])
        asked (first (filter #(= :spawn (get-in % [:req :op])) (:to-api outs)))
        [st outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r (contract/error :seon.root/refused 'seon.root/spawn! held-why {:branch :demo/a :agent :a9})})
        w (first (filter #(= :write (get-in % [:req :op])) (:to-api outs2)))
        [st outs3] (r/reconciler-proc st :answers {:tag (:tag w) :r {:ok true}})
        [db2 ops2] (commit db (get-in w [:req :req :tx-data]))
        [st outs4] (main st db2 ops2)
        [db3 ops3] (commit db2 [{:seon.task/id "demo/a" :seon.task/plan "Make a true, again."}])
        [_ outs5] (main st db3 ops3)]
    (is (= ["demo/a"] (spawns outs)))
    (is (= [] (spawns outs3)) "the write's answer starts nothing")
    (is (= [] (spawns outs4)) "nor does the refused answer's own commit")
    (is (= [{:task "demo/a" :cause held-why :run "demo/a#1" :blocks []}] (get-in st [:quiescent :root-causes])) "quiet, naming it")
    (is (= ["demo/a"] (spawns outs5)) "other work on main: it asks again")))

(deftest quiescence-names-a-task-that-cannot-start
  ;; a refused run is a root cause at quiescence, naming what it blocks
  (let [db (db-of [(decl "a") (decl "b" :needs ["a"])]
                  :extra (concat (run "a" 1 :refused) [{:seon.run/id "demo/a#1" :seon.run/reason held-why}]))]
    (is (= [{:task "demo/a" :cause held-why :run "demo/a#1" :blocks ["demo/b"]}] (r/root-causes (r/index db))))))

(deftest a-task-marked-failed-is-held-for-root
  ;; owner, 2026-10-22: a failure is task data and root reacts to it; D2(a): held for root,
  ;; no automatic retry. my.task/fail! writes the failure on the branch; root's judge
  ;; carries this run's onto main with the task held, and the run closes
  (let [main (db-of [(decl "a")] :extra (run "a" 1 :running))
        f {:reason "the premise is refuted" :counterexample "(= 1 2)" :run "demo/a#1"}
        branch (d/db-with main [{:seon.task/id "demo/a" :seon.task/failure f}])
        judge #(#'r/judge-tx {:db main :boot "boot-1" :rid 0 :flights {} :pending {}} {:act {:run "demo/a#1" :task "demo/a"}} {} {:db % :base main :ours [] :uncontracted [] :unmerged []})
        judged (d/db-with main (judge branch))
        idx (r/index judged)]
    (is (= :held (:seon.task/execution (task/task judged "demo/a"))) "held for root, without a merge")
    (is (= f (:seon.task/failure (task/task judged "demo/a"))) "the failure, on main")
    (is (= :failed (r/task-state idx "demo/a")))
    (is (= [{:op :close :run "demo/a#1"}] (actions-for judged)) "the run ends; nothing refines or retries it")
    (let [[row & more] (get-in (problems/report judged) [:classes :failed :rows])]
      (is (nil? more))
      (is (= {:task "demo/a" :reason "the premise is refuted" :counterexample "(= 1 2)" :open? true}
             (select-keys row [:task :reason :counterexample :open?])) "root is told: a :failed row, the one producer of what is wrong"))
    (let [released (d/db-with judged (task/execution-tx ["demo/a"] :running))]
      (is (not= :failed (r/task-state (r/index released) "demo/a")) "a release is root's answer")
      (is (false? (:open? (first (get-in (problems/report released) [:classes :failed :rows])))) "and the row closes"))
    (is (nil? (:seon.task/failure (task/task (d/db-with main (judge (d/db-with main [{:seon.task/id "demo/a" :seon.task/failure (assoc f :run "demo/a#0")}])))
                                            "demo/a")))
        "an earlier run's failure on a reused branch is not this run's")))

(deftest a-branch-carries-every-task-it-changed-and-records-a-two-sided-edit
  ;; owner, 2026-10-22 (D4): when a branch's run ends, a task version newer than main's
  ;; overwrites it; one edited on both sides differently is a conflict for root
  (let [base (db-of [(decl "a") (decl "b") (decl "c")] :extra (run "a" 1 :running))
        branch (d/db-with base [{:seon.task/id "demo/b" :seon.task/plan "b, as a's agent learned it"}
                                {:seon.task/id "demo/c" :seon.task/plan "c, the branch's"}])
        main (d/db-with base [{:seon.task/id "demo/c" :seon.task/plan "c, main's"}])
        judged (d/db-with main (#'r/judge-tx {:db main :boot "boot-1" :rid 0 :flights {} :pending {}}
                                             {:act {:run "demo/a#1" :task "demo/a"}} {} {:db branch :base base :ours [] :uncontracted [] :unmerged []}))
        conflict {:task "demo/c" :attr :seon.task/plan :base "Make c true." :ours "c, the branch's" :theirs "c, main's"}]
    (is (= "b, as a's agent learned it" (:seon.task/plan (task/task judged "demo/b"))) "another task's edit reaches main at the run's end")
    (is (= "Make a true." (:seon.task/plan (task/task judged "demo/a"))) "an unchanged task stays as main holds it")
    (is (= "c, main's" (:seon.task/plan (task/task judged "demo/c"))) "a two-sided edit is never resolved here")
    (is (= [conflict] (:seon.run/task-conflicts (d/pull judged [:seon.run/task-conflicts] [:seon.run/id "demo/a#1"]))))
    (is (= [(assoc conflict :from "demo/a#1")] (map :evidence (get-in (problems/report judged) [:classes :task-conflict :rows])))
        "root sees it among the problems")
    (testing "main's task plans never put a merge behind: merging merges the program, no task data"
      (is (empty? (:theirs (pm/three-way (pm/program base #{}) (pm/program branch #{}) (pm/program main #{}))))))))

(deftest a-waiting-run-parks-and-a-message-wakes-it
  ;; design agent-loop sections 1-2 (owner, 2026-10-25: "Always park"): a run that ends waiting,
  ;; out of turns or without progress parks its session. It is closed, never held, and its task
  ;; stays released. Until a message to the session comes, the task does not start again; once one
  ;; comes, its next run is a wake on the parked process when that process is of this boot
  (let [red {(t "a") :fail}
        parked (fn [outcome taken] (into (run "a" 1 :parked :results red :outcome outcome)
                                         [{:seon.run/id "demo/a#1" :seon.run/taken taken}]))
        msg (fn [mid] (task/message-tx mid :message "user" "demo/a#1" "carry on"))
        wake {:op :wake :task "demo/a" :run "demo/a#2" :agent :a-a1
              :env {:branch (task/default-branch "demo/a") :model nil :max-turns (cfg/value :seon.config.agent/max-turns)}}]
    (testing "judged: a park outcome parks the run; a red outcome is a verdict to the agent"
      (is (= [{:op :park :run "demo/a#1"}]
             (actions-for (db-of [(decl "a")] :extra (run "a" 1 :ended :results red :outcome :waiting)))))
      (is (= :verdict (:op (first (actions-for (db-of [(decl "a")] :extra (run "a" 1 :ended :results red :outcome :red))))))))
    (testing "parked, nothing new: nothing starts"
      (is (= [] (actions-for (db-of [(decl "a")] :extra (parked :waiting 0)))))
      (is (= [] (actions-for (db-of [(decl "a")] :extra (into (parked :cap 1) (msg "m1"))))) "one message, already taken")
      (is (= [{:task "demo/a" :cause "parked: cap, waiting for a message" :run "demo/a#1" :blocks ["demo/b"]}]
             (r/root-causes (r/index (db-of [(decl "a") (decl "b" :needs ["a"])] :extra (parked :cap 0)))))
          "quiet names it, with what it blocks"))
    (testing "a wake in flight: more messages start nothing more, since one process runs one work! (flow-lab's hazard 4); its run takes them, or parks short of them and wakes again"
      (is (= [] (actions-for (db-of [(decl "a")] :extra (concat (parked :waiting 0) (run "a" 2 :running) (msg "m1") (msg "m2")))))))
    (testing "a message it has not taken: a wake on its process (one that came while its run ran is counted too: flow-lab's lost wake, hazard 3)"
      (is (= [wake] (actions-for (db-of [(decl "a")] :extra (into (parked :waiting 0) (msg "m1"))))))
      (is (= [wake] (actions-for (db-of [(decl "a")] :extra (into (parked :cap 1) (concat (msg "m1") (msg "m2"))))))))
    (testing "parked, its task cancelled or held: its process is stopped (a kill of the parked run), nothing starts"
      (is (= [{:op :kill :run "demo/a#1" :reason :cancelled}]
             (actions-for (db-of [(decl "a")] :extra (into (parked :waiting 0) (concat (msg "m1") (task/execution-tx ["demo/a"] :cancelled)))))))
      (is (= [{:op :kill :run "demo/a#1" :reason :held}]
             (actions-for (db-of [(decl "a")] :extra (into (parked :waiting 0) (task/execution-tx ["demo/a"] :held)))))))
    (testing "parked in an earlier boot: its process is gone, so the run is a spawn (a load of the session)"
      (is (= [(spawn "a" 2)] (actions-for (db-of [(decl "a")] :extra (into (into (run "a" 1 :parked :results red :outcome :waiting :boot "boot-0")
                                                                                   [{:seon.run/id "demo/a#1" :seon.run/taken 0}])
                                                                             (msg "m1")))))))
    (testing "it waited with its proofs green: done, never parked (start stops its kept process)"
      (is (= [{:op :accept :run "demo/a#1" :task "demo/a"}]
             (actions-for (db-of [(decl "a")] :extra (run "a" 1 :ended :results {(t "a") :pass} :outcome :waiting))))))
    (testing "its task moved to another branch: the parked process (forked on the old one) is stopped, and the next run is a spawn"
      (is (= [{:op :kill :run "demo/a#1" :reason :env-changed}
              (assoc-in (spawn "a" 2) [:env :branch] :elsewhere)]
             (actions-for (db-of [(decl "a")] :extra (concat (parked :waiting 0) (msg "m1")
                                                             [{:seon.task/id "demo/a" :seon.task/branch :elsewhere}]))))))
    (testing "the message count is observed at each commit as listing finds it, whichever way it came"
      (let [db0 (db-of [(decl "a")] :extra (parked :waiting 0))]
        (reduce (fn [[db idx] tx]
                  (let [{db' :db-after txd :tx-data} (d/with db tx)
                        ops (for [dt txd] [(if (:added dt) :db/add :db/retract) (:e dt) (:a dt) (:v dt)])
                        listed (r/index db')]
                    (is (= listed (r/observe idx db' ops)) "a commit's ops")
                    (is (= listed (r/index idx db db')) "a diff")
                    [db' listed]))
                [db0 (r/index db0)] [(msg "m1") (msg "m2")])
        (is (= 2 (get-in (r/index (d/db-with db0 (concat (msg "m1") (msg "m2")))) [:msgs (task/default-branch "demo/a")])))))))

(deftest a-sessions-next-stage-opens-with-roots-message
  ;; live finding A1 (2026-10-25): a session's next stage (another task, the same agent) opens
  ;; with root's message naming the new task and its task data; a wake of the same task opens with none
  (let [on (fn [run task] {:seon.run/id run :seon.run/task [:seon.task/id (str "demo/" task)] :seon.run/agent {:seon.agent/key "b/a1"}})
        db (db-of [(decl "d") (decl "i" :needs ["d"])] :extra [(on "demo/d#1" "d")])
        db (d/db-with db [(on "demo/i#1" "i")])
        body (#'r/stage-boundary db "demo/i#1")]
    (is (str/starts-with? body "demo/d is done. Your task now is demo/i") body)
    (is (str/includes? body "Its plan: Make d true.\n\nMake i true.\n\nIts proofs: demo-test/d-test demo-test/i-test") "with the new task's plan and proofs, as they accumulated")
    (is (str/includes? body "demo-test/i-test") "and its proofs")
    (is (nil? (#'r/stage-boundary db "demo/d#1")) "a session's first run opens with none")
    (is (nil? (#'r/stage-boundary (d/db-with db [(on "demo/i#2" "i")]) "demo/i#2")) "nor does a wake of the same task")))

(deftest a-proof-a-branch-registers-reaches-main-at-the-runs-end
  ;; owner, 2026-10-25 (Dogfood rulings): a design registers the red proofs it writes
  ;; (my.task/update! :seon.task/proofs conj 't); the judge carries them onto main like the plan,
  ;; so the done rule sees them at design and the implement step inherits them
  (let [base (db-of [(decl "a" :proofs #{}) (decl "b" :proofs #{'demo-test/kept-test 'demo-test/dropped-test})] :extra (run "a" 1 :running))
        branch (d/db-with base [[:db/add [:seon.task/id "demo/a"] :seon.task/proofs 'demo-test/a-design-wrote-test]
                                [:db/retract [:seon.task/id "demo/b"] :seon.task/proofs 'demo-test/dropped-test]])
        judged (d/db-with base (#'r/judge-tx {:db base :boot "boot-1" :rid 0 :flights {} :pending {}}
                                             {:act {:run "demo/a#1" :task "demo/a"}} {} {:db branch :base base :ours [] :uncontracted [] :unmerged []}))]
    (is (= #{'demo-test/a-design-wrote-test} (set (:seon.task/proofs (task/task judged "demo/a")))) "an added proof reaches main")
    (is (= #{'demo-test/kept-test} (set (:seon.task/proofs (task/task judged "demo/b")))) "a dropped one leaves it")
    (is (= #{'demo-test/a-design-wrote-test} (get-in (r/index judged) [:acc "demo/a"])) "the done rule's input holds it")
    (testing "the implement step that needs the design inherits it, observed as the reconciler does (live finding F3)"
      (let [base (d/db-with base (task/create-tx base [(decl "c" :needs ["a"] :proofs #{})]))
            branch (d/db-with base [[:db/add [:seon.task/id "demo/a"] :seon.task/proofs 'demo-test/a-design-wrote-test]])
            judge (fn [main aid id] (d/db-with main (#'r/judge-tx {:db main :boot "boot-1" :rid 0 :flights {} :pending {}}
                                                                  {:act {:run aid :task id}} {} {:db branch :base base :ours [] :uncontracted [] :unmerged []})))
            judged (judge (d/db-with base (into (run "a" 1 :running) (task/execution-tx ["demo/c"] :running))) "demo/a#1" "demo/a")
            idx0 (r/index base)]
        (is (= #{'demo-test/a-design-wrote-test} (get-in (r/index judged) [:acc "demo/c"])) "listed")
        (is (= #{'demo-test/a-design-wrote-test} (get-in (r/index idx0 base judged) [:acc "demo/c"])) "observed from the index before")
        (let [judged2 (judge (d/db-with judged (run "c" 1 :running)) "demo/c#1" "demo/c")]
          (is (= #{'demo-test/a-design-wrote-test} (:seon.task/proofs (task/task judged2 "demo/a")))
              "the implement's judge, on the same branch from the same base, keeps the design's proof on main")
          (is (= #{'demo-test/a-design-wrote-test} (get-in (r/index judged2) [:acc "demo/c"]))))))
    (testing "a proof set main changed too, differently, is a conflict for root"
      (let [main (d/db-with base [[:db/add [:seon.task/id "demo/b"] :seon.task/proofs 'demo-test/mains-test]])
            {:keys [tx conflicts]} (task/carry-tx base branch main)]
        (is (not-any? #(= [:seon.task/id "demo/b"] (second %)) tx))
        (is (= [{:task "demo/b" :attr :seon.task/proofs :base #{'demo-test/kept-test 'demo-test/dropped-test}
                 :ours #{'demo-test/kept-test} :theirs #{'demo-test/kept-test 'demo-test/dropped-test 'demo-test/mains-test}}]
               (filter #(= "demo/b" (:task %)) conflicts)))))))
