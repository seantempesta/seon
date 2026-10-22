(ns seon.reconcile-test
  "(reconcile db) answers exactly the expected actions for a table of task graphs: needs
  DAGs, max-running, held and cancelled tasks, environment changes, a previous root's
  attempts, ended attempts judged by the done rule, dead agents."
  (:require [seon.test-store :as ts] [seon.profile :as profile] [clojure.test :refer [deftest is are testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.task :as task]
            [seon.reconcile :as r]
            [seon.template :as tpl] [seon.problems :as problems] [seon.merge :as pm] [seon.sock :as sock] [clojure.edn] [seon.contract :as contract]
            [clojure.string :as str] [clojure.set] [seon.config :as cfg]))

(defn- empty-db [] (ts/test-db))

(defn- decl [nm & {:keys [needs proofs model]}]
  (cond-> {:seon.task/ns 'demo :seon.task/name nm :seon.task/plan (str "Make " nm " true.")
           :seon.task/proofs (or proofs #{(symbol "demo-test" (str nm "-test"))})}
    needs (assoc :seon.task/needs (set (map #(str "demo/" %) needs)))
    model (assoc :seon.task/model model)))

(def diamond [(decl "a") (decl "b" :needs ["a"]) (decl "c" :needs ["a"]) (decl "d" :needs ["b" "c"])])

(defn- attempt
  "An attempt fact of task nm: status, plus members (results as {test status}; conflicts
  as :seon.merge/conflict values; unmerged as printed keys)."
  [nm n status & {:keys [boot model results outcome conflicts unmerged changed plan] :or {boot "boot-1" plan (str "Make " nm " true.")}}]
  (let [id (str "demo/" nm) aid (str id "#" n)]
    (into (task/attempt-start-tx aid id boot (keyword (str "a-" nm n))
                                 {:branch (task/default-branch id) :model model :max-turns r/default-max-turns} 1000 plan)
          [(cond-> {:seon.attempt/id aid :seon.attempt/status status}
             results (assoc :seon.attempt/results (vec (for [[t s] results] {:seon.proof/test t :seon.proof/status s})))
             outcome (assoc :seon.attempt/outcome outcome)
             conflicts (assoc :seon.attempt/conflicts (mapv pm/conflict-tx conflicts))
             unmerged (assoc :seon.attempt/unmerged unmerged)
             changed (assoc :seon.attempt/changed changed))])))

(defn- db-of
  "A main db: decls written, the ids in `released` released (all when :all), root's boot,
  then extra tx-data (attempts, execution changes)."
  [decls & {:keys [released max-running extra] :or {released :all max-running 4}}]
  (let [db (empty-db)
        db (d/db-with db (task/create-tx db decls))
        ids (if (= :all released) (map #(task/task-id 'demo (:seon.task/name %)) decls) released)]
    (cond-> (-> db (d/db-with (task/execution-tx (vec ids) :running))
                (d/db-with (task/boot-tx "boot-1" max-running)))
      (seq extra) (d/db-with extra))))

(defn- merged [nm] (attempt nm 1 :merged))
(defn- spawn [nm n & {:keys [model]}]
  {:op :spawn :task (str "demo/" nm) :attempt (str "demo/" nm "#" n)
   :env {:branch (task/default-branch (str "demo/" nm)) :model model :max-turns r/default-max-turns}})
(defn- t [nm] (symbol "demo-test" (str nm "-test")))

(deftest task-state-is-what-happened
  ;; the one derivation of a task's observed state, from the index (seon.render.proc/task-rows reads it)
  (let [db (db-of diamond :extra (into (merged "a") (concat (attempt "b" 1 :done) (attempt "c" 1 :running))))
        idx (r/index db)]
    (is (= {"demo/a" :merged "demo/b" :done "demo/c" :running "demo/d" :blocked}
           (into {} (for [id ["demo/a" "demo/b" "demo/c" "demo/d"]] [id (r/task-state idx id)]))))
    (is (= :held (r/task-state (r/index (db-of diamond :released [])) "demo/a")) "never released")))

(deftest needs-dag-starts-in-dependency-order
  (are [db expected] (= expected (r/reconcile db))
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

(deftest max-running-caps-parallel-attempts
  (let [three [(decl "x") (decl "y") (decl "z")]]
    (are [db expected] (= expected (r/reconcile db))
      (db-of three :max-running 1) [(spawn "x" 1)]
      (db-of three :max-running 2) [(spawn "x" 1) (spawn "y" 1)]
      ;; x running: one slot left of two
      (db-of three :max-running 2 :extra (attempt "x" 1 :running)) [(spawn "y" 1)]
      ;; two running: none left
      (db-of three :max-running 2 :extra (concat (attempt "x" 1 :running) (attempt "y" 1 :running))) [])))

(deftest nothing-autoruns-held-and-cancelled
  (let [three [(decl "x") (decl "y") (decl "z")]]
    (are [db expected] (= expected (r/reconcile db))
      ;; written tasks are held
      (db-of three :released []) []
      ;; only the released one starts
      (db-of three :released ["demo/y"]) [(spawn "y" 1)]
      ;; cancelled after release
      (db-of three :released ["demo/y"] :extra (task/execution-tx ["demo/y"] :cancelled)) [])))

(deftest cancel-kills-and-blocks-dependents
  (let [db (db-of diamond :extra (concat (merged "a") (attempt "b" 1 :running) (attempt "c" 1 :running)
                                         (task/execution-tx ["demo/b"] :cancelled)))]
    (is (= [{:op :kill :attempt "demo/b#1" :reason :cancelled}] (r/reconcile db)))
    (is (= #{"demo/b" "demo/c"} (r/blocked-by (r/index db) "demo/d")))
    ;; killed: nothing restarts it, and d stays blocked once c merges
    (let [db (d/db-with db (concat (task/status-tx db "demo/b#1" :running :killed {})
                                   (task/status-tx db "demo/c#1" :running :merged {})))]
      (is (= [] (r/reconcile db)))
      (is (= #{"demo/b"} (r/blocked-by (r/index db) "demo/d"))))))

(deftest held-kills-without-restart
  (let [db (db-of [(decl "x")] :extra (concat (attempt "x" 1 :running) (task/execution-tx ["demo/x"] :held)))]
    (is (= [{:op :kill :attempt "demo/x#1" :reason :held}] (r/reconcile db)))))

(deftest environment-change-restarts-clean
  (let [db (db-of [(decl "x" :model "m1")] :extra (concat (attempt "x" 1 :running :model "m1")
                                                          (task/env-tx "demo/x" {:model "m2"})))]
    (is (= [{:op :kill :attempt "demo/x#1" :reason :env-changed}] (r/reconcile db)))
    (let [db (d/db-with db (task/status-tx db "demo/x#1" :running :killed {}))]
      (is (= [(spawn "x" 2 :model "m2")] (r/reconcile db))))))

(deftest a-previous-roots-attempts-are-reaped-and-resumed
  (let [db (db-of [(decl "x") (decl "y" :needs ["x"])] :extra (attempt "x" 1 :running :boot "boot-0"))]
    (is (= [{:op :reap :attempt "demo/x#1"} (spawn "x" 2)] (r/reconcile db)))
    (is (= #{} (r/running (r/index db))))))

(deftest ended-attempts-are-judged-by-the-done-rule
  (are [extra expected] (= expected (r/reconcile (db-of diamond :extra extra)))
    ;; green: a work task is accepted on its branch
    (attempt "a" 1 :ended :outcome :green :results {(t "a") :pass (t "b") :fail})
    [{:op :accept :attempt "demo/a#1" :task "demo/a"}]
    ;; its proof red: the task is held for root
    (attempt "a" 1 :ended :outcome :cap :results {(t "a") :fail})
    [{:op :hold :attempt "demo/a#1" :task "demo/a" :problem "attempt demo/a#1 ended cap: proof-red demo-test/a-test"}]
    ;; its proof never ran: unknown, which refuses done as red does
    (attempt "a" 1 :ended :outcome :green :results {})
    [{:op :hold :attempt "demo/a#1" :task "demo/a" :problem "attempt demo/a#1 ended green: proof-unknown demo-test/a-test"}]
    ;; b needs a, so b carries a's proof (accumulated along :needs): red there is b's own red
    (concat (merged "a") (attempt "b" 1 :ended :outcome :green :results {(t "a") :fail (t "b") :pass}))
    [{:op :hold :attempt "demo/b#1" :task "demo/b" :problem "attempt demo/b#1 ended green: proof-red demo-test/a-test"}
     {:op :spawn :task "demo/c" :attempt "demo/c#1"
      :env {:branch :task-demo-c :model nil :max-turns r/default-max-turns}}]
    ;; a stray red: a red test that is no task's proof
    (attempt "a" 1 :ended :outcome :green :results {(t "a") :pass 'demo-test/other :error})
    [{:op :hold :attempt "demo/a#1" :task "demo/a" :problem "attempt demo/a#1 ended green: stray-red demo-test/other"}]
    ;; its agent died
    (attempt "a" 1 :dead)
    [{:op :hold :attempt "demo/a#1" :task "demo/a" :problem "its agent died"}]))

(deftest an-attempt-with-no-end-records-no-fake-spend
  ;; task seon.model/budgets-are-data-and-spend-is-measured: the agent wrote no end (it died
  ;; or was killed before end!), so its turns, tokens and time are unknown: absent, never 0
  (let [db (db-of diamond :extra (attempt "a" 1 :running))
        tx (#'r/judge-tx {:db db :boot "boot-1" :rid 0 :flights {} :pending {}} {:act {:attempt "demo/a#1" :task "demo/a"}} {} {:db db :base db} #{})
        ended (first (filter #(and (map? %) (= "demo/a#1" (:seon.attempt/id %)) (:seon.attempt/outcome %)) tx))]
    (is (= :no-end (:seon.attempt/outcome ended)))
    (is (= #{} (set (filter #{:seon.attempt/turns :seon.attempt/model-calls :seon.attempt/tokens :seon.attempt/ms} (keys ended)))))))

(deftest a-death-names-its-cause
  ;; task seon.agent/a-death-names-its-cause: a dead agent's attempt carries the end it wrote
  ;; on its branch before it exited (its cause), beside the death root observed (exit status);
  ;; a silent death (no end) is named by its exit; a turn loop that threw (the agent's REPL
  ;; answered the error, the process lives) is named by that error, not "wrote no end"
  (let [died (contract/error :seon.agent/died 'seon.root/api-proc "agent a1 died: exit 70" {:agent :a1 :exit {:exit 70}})
        cause (contract/error :seon.agent/eval-error 'seon.repl/serve! "boom" {:class "clojure.lang.ExceptionInfo"})
        row (fn [tx] (first (filter #(and (map? %) (:seon.attempt/reason %)) tx)))
        err (fn [tx] (:seon.attempt/error (row tx)))]
    (testing "an end written before the death: its reason and cause, and the exit"
      (let [tx (#'r/dead-tx "demo/a#1" died {:outcome :error :reason "the agent's process died: boom" :error cause})]
        (is (= "the agent's process died: boom (agent a1 died: exit 70)" (:seon.attempt/reason (row tx))))
        (is (= "boom" (get-in (err tx) [:cause :seon.error/message])))
        (is (= {:exit 70} (:exit (err tx))))))
    (testing "a silent death: named by its exit"
      (let [tx (#'r/dead-tx "demo/a#1" (assoc died :exit {:signal 9} :seon.error/message "agent a1 died: signal 9") {})]
        (is (= "agent a1 died: signal 9" (:seon.attempt/reason (row tx))))
        (is (= {:signal 9} (:exit (err tx))))))
    (testing "the turn loop threw and the agent lives: its error is the attempt's reason"
      (let [db (db-of diamond :extra (attempt "a" 1 :running))
            tx (#'r/judge-tx {:db db :boot "boot-1" :rid 0 :flights {} :pending {}}
                             {:act {:attempt "demo/a#1" :task "demo/a"} :worked-error cause} {} {:db db :base db} #{})]
        (is (= "seon.turn/work! threw: boom" (:seon.attempt/reason (row tx))))
        (is (= "boom" (:seon.error/message (err tx))))))))

(deftest an-ended-attempt-of-a-cancelled-task-is-closed
  (is (= [{:op :close :attempt "demo/a#1"}]
         (r/reconcile (db-of diamond :extra (concat (attempt "a" 1 :ended :outcome :green :results {(t "a") :pass})
                                                    (task/execution-tx ["demo/a"] :cancelled)))))))

(deftest done-refusals-table
  (are [in expected] (= expected (mapv (juxt :refusal :symbol) (r/done-refusals in)))
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

(deftest regression-is-judged-against-main-at-the-attempts-basis
  ;; x merged at main t 100; y (independent of x) was observed against main at t 50,
  ;; before x's merge, so x's red proof there is not a regression of the main y holds
  (let [two [(decl "x") (decl "y")]
        merged-x (into (attempt "x" 1 :merged) [{:seon.attempt/id "demo/x#1" :seon.attempt/merged-t 100}])
        y (fn [basis] (into (attempt "y" 1 :ended :outcome :green :results {(t "x") :fail (t "y") :pass})
                            [{:seon.attempt/id "demo/y#1" :seon.attempt/basis-t basis}]))]
    (is (= {:op :accept :attempt "demo/y#1" :task "demo/y"}
           (first (r/reconcile (db-of two :extra (concat merged-x (y 50)))))))
    (is (= :hold (:op (first (r/reconcile (db-of two :extra (concat merged-x (y 150))))))))))

(deftest contracts-are-checked
  ;; dev/test.clj arms every contract before the tests run: a call outside its contract is
  ;; refused at entry with the one error map
  (when (not= "off" (System/getenv "PROTO_GUARANTEES"))
    (is (= :seon.instrument/contract-error
           (:seon.error/type (try (task/task-id "not-a-symbol" "x") (catch Exception e (ex-data e))))))))

;; ---- true branches: merge tasks, unmerged entries, behind, a refused attempt held for root ----

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

(defn- mspawn [nm n] {:op :spawn :task (str "demo/" nm) :attempt (str "demo/" nm "#" n)
                      :env {:branch (task/default-branch (str "demo/" (str/replace nm #"-merge$" ""))) :model nil :max-turns r/default-max-turns}})

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
             (mapv (juxt :refusal :symbol)
                   (r/done-refusals (r/done-input (r/index db) "demo/b-merge" {(t "b") :pass (t "a") :fail} #{} [] nil nil))))))))

(deftest with-merge-chains-work-through-merges
  (let [db (mdb [(decl "a") (decl "b" :needs ["a"])])]
    (is (= {:seon.task/kind :merge :seon.task/needs #{"demo/a"} :seon.task/branch :task-demo-a}
           (select-keys (task/task db "demo/a-merge") [:seon.task/kind :seon.task/needs :seon.task/branch])))
    (is (= #{"demo/a-merge"} (:seon.task/needs (task/task db "demo/b"))))
    (testing "a's work first; accepted on its branch, its merge task starts on the same branch"
      (is (= [(mspawn "a" 1)] (r/reconcile db)))
      (let [db (d/db-with db (attempt "a" 1 :done))]
        (is (= [(mspawn "a-merge" 1)] (r/reconcile db)))
        (testing "b waits for a's merge, not a's work"
          (is (= #{"demo/a-merge"} (r/blocked-by (r/index db) "demo/b"))))))))

(deftest green-merge-attempt-merges
  ;; an octopus merge of one: no other merge attempt runs
  (is (= [{:op :octopus-merge :attempt "demo/a-merge#1" :members [{:attempt "demo/a-merge#1" :task "demo/a-merge"}]}]
         (r/reconcile (mdb [(decl "a")] :extra (concat (attempt "a" 1 :done)
                                                       (attempt "a-merge" 1 :ended :outcome :green :results {(t "a") :pass}))))))
  ;; (the attempt helper names branches by task id; only the action matters here)
  )

(deftest merges-run-one-at-a-time
  ;; git's merge queue: a merge agent's last merge-main and root's merge see the same main,
  ;; so a merge is never behind; work tasks still run in parallel
  (let [three [(decl "x") (decl "y") (decl "z")]
        done-work (mapcat #(attempt % 1 :done) ["x" "y" "z"])]
    (are [extra expected] (= expected (map :task (filter #(= :spawn (:op %)) (r/reconcile (mdb three :extra extra)))))
      done-work ["demo/x-merge"]
      ;; one merge running: none starts
      (concat done-work (attempt "x-merge" 1 :running)) []
      ;; one ended, awaiting root's merge: still the queue's
      (concat done-work (attempt "x-merge" 1 :ended :outcome :green :results {(t "x") :pass})) []
      ;; merged: the next one starts
      (concat done-work (attempt "x-merge" 1 :merged)) ["demo/y-merge"])
    (testing "work tasks are not queued"
      (is (= ["demo/x" "demo/y" "demo/z"] (map :task (r/reconcile (mdb three))))))))

(deftest boot-records-merge-max-running-one
  ;; DECISIONS: octopus merges stay off (one merge at a time) until the octopus merge's combined test run
  ;; and the par-200 hang are fixed; the config value in force is a fact on main a person can read
  (let [db (d/db-with (empty-db) (into (task/boot-tx "boot-1" 4) (cfg/facts cfg/config {})))]
    (is (= 1 r/default-max-merging))
    (is (= {:boot "boot-1" :max-running 4 :max-merging 1} (task/root db)) "both config keys are recorded at boot")))

(deftest two-ready-disjoint-merges-run-one-at-a-time-by-default
  ;; x and y change disjoint definitions: an octopus merge could take both, but the config says 1
  (let [two [(decl "x") (decl "y")]
        extra (concat (attempt "x" 1 :done :changed #{"[:var app.m/x]"}) (attempt "y" 1 :done :changed #{"[:var app.m/y]"}))]
    (is (= ["demo/x-merge"] (map :task (filter #(= :spawn (:op %)) (r/reconcile (mdb two :extra extra))))))
    (testing "an octopus merge is turned on by writing the fact"
      (is (= ["demo/x-merge" "demo/y-merge"]
             (map :task (filter #(= :spawn (:op %)) (r/reconcile (mdb two :extra (concat extra [{:seon.root/id :root :seon.config.merge/max-running 8}]))))))))))

(deftest unmerged-refuses-done
  (is (= [{:op :hold :attempt "demo/a#1" :task "demo/a"
           :problem "attempt demo/a#1 ended green: unmerged [:var app/f]"}]
         (r/reconcile (db-of diamond :extra (attempt "a" 1 :ended :outcome :green :results {(t "a") :pass}
                                                     :unmerged ["[:var app/f]"]))))))

(deftest behind-merge-gets-a-new-attempt
  (testing "root's merge found main moved: the merge task's next attempt is a new agent; no catch-up"
    (let [db (mdb [(decl "a")] :extra (concat (attempt "a" 1 :done) (attempt "a-merge" 1 :behind :outcome :green)))]
      (is (= [(mspawn "a-merge" 2)] (r/reconcile db))))))

(deftest design-may-leave-its-proofs-red
  ;; a design step's work is the plan: done only when the plan differs from the one it began with
  (are [in expected] (= expected (mapv :refusal (r/done-refusals in)))
    {:kind :design :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{} :plan "B" :plan-before "A"} []
    {:kind :design :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{'app/f} :plan "B" :plan-before "A"} [:contract-gap]
    {:kind :design :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{} :plan "A" :plan-before "A"} [:no-design]
    {:kind :work :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{}} [:proof-red]))

(defn- design-db
  "Main with design task demo/a released, its attempt #1 ended as status, extra after."
  [extra]
  (let [db (empty-db)
        d [(assoc (decl "a") :seon.task/kind :design)]
        db (d/db-with db (task/create-tx db d))]
    (-> db (d/db-with (task/execution-tx ["demo/a"] :running)) (d/db-with (task/boot-tx "boot-1" 4)) (d/db-with extra))))

(deftest a-design-step-is-not-done-at-its-start
  ;; measured 2026-10-21: a design attempt ended :done :green with 0 turns; its proofs may stay
  ;; red, so at its start nothing refused it
  (testing "at its start (the plan as it began), the done rule refuses :no-design"
    (let [db (design-db (attempt "a" 1 :running))]
      (is (= [:no-design]
             (mapv :refusal (r/done-refusals (r/done-input (r/index db) "demo/a" {(t "a") :fail} #{} []
                                                           nil (:seon.attempt/plan (task/attempt db "demo/a#1")))))))))
  (testing "ended green with the plan unchanged: refused, so it is held for root, never accepted"
    (is (= [{:op :hold :attempt "demo/a#1" :task "demo/a" :problem "attempt demo/a#1 ended green: no-design"}]
           (r/reconcile (design-db (attempt "a" 1 :ended :outcome :green :results {(t "a") :fail})))))))

(deftest a-design-step-is-done-when-it-improved-the-plan
  ;; root's judge carried the plan the attempt improved on its branch to main (judge-tx)
  (is (= [{:op :accept :attempt "demo/a#1" :task "demo/a"}]
         (r/reconcile (design-db (concat (attempt "a" 1 :ended :outcome :green :results {(t "a") :fail})
                                         [{:seon.task/id "demo/a" :seon.task/plan "Make a true. Design: (defn f [xs] ...) one pass over xs."}]))))))

(defn- held
  "main db after the hold the reconciler's one action for it writes (its :hold op), and the action."
  [db]
  (let [[act :as acts] (r/reconcile db)]
    (assert (= [:hold] (map :op acts)) (pr-str acts))
    [(d/db-with db (r/hold-tx (task/attempt db (:attempt act)) (:problem act))) act]))

(deftest a-refused-attempt-holds-its-task-and-creates-none
  ;; owner, 2026-10-23: "no automatic retries, send it to root to decide if it should be
  ;; retried". Every refusal holds its task as my.task/fail! does: its failure (the reason,
  ;; the attempt), the measured evidence, the attempt closed; no task is created or cancelled
  (doseq [[why extra problem]
          [["a red proof" (attempt "add" 1 :ended :outcome :cap :results {(t "add") :fail})
            "attempt demo/add#1 ended cap: proof-red demo-test/add-test"]
           ["an error, its proof never run" (attempt "add" 1 :ended :outcome :error :results {})
            "attempt demo/add#1 ended error: proof-unknown demo-test/add-test"]
           ["a dead agent" (attempt "add" 1 :dead) "its agent died"]
           ["a merge conflict" (concat (attempt "add" 1 :done)
                                       (attempt "add-merge" 1 :ended :outcome :cap :results {(t "add") :pass}
                                                :conflicts [conflict-f] :unmerged ["[:var app/f]"]))
            "attempt demo/add-merge#1 ended cap: unmerged [:var app/f]"]]]
    (testing why
      (let [db (mdb [(decl "add") (decl "twice" :needs ["add"])] :extra extra)
            [db' act] (held db)
            id (:task act) aid (:attempt act)
            tk (task/task db' id)]
        (is (= problem (:problem act)))
        (is (= (set (keys (task/tasks db))) (set (keys (task/tasks db')))) "no new task")
        (is (not-any? #(= :cancelled (:seon.task/execution %)) (vals (task/tasks db'))) "none cancelled")
        (is (= :held (:seon.task/execution tk)))
        (is (= {:reason problem :attempt aid} (:seon.task/failure tk)))
        (is (= aid (:from (:seon.task/evidence tk))))
        (is (= :closed (:seon.attempt/status (task/attempt db' aid))) "the attempt is answered once")
        (is (= :failed (r/task-state (r/index db') id)))))))

(deftest a-held-task-shows-its-reason-among-the-problems
  (let [[db' act] (held (mdb [(decl "add")] :extra (attempt "add" 1 :ended :outcome :cap :results {(t "add") :fail})))
        [row & more] (get-in (problems/report db') [:classes :failed :rows])]
    (is (nil? more))
    (is (= {:task "demo/add" :attempt "demo/add#1" :reason (:problem act) :open? true}
           (select-keys row [:task :attempt :reason :open?])))
    (is (= [(t "add")] (get-in row [:evidence :red])) "with its measured evidence")
    (is (nil? (get-in (problems/report db') [:classes :stuck])) "one class for a held task: :failed")))

(deftest roots-release-runs-again-on-the-same-branch-with-the-evidence
  (let [db (mdb [(decl "add")] :extra (attempt "add" 1 :ended :outcome :cap :results {(t "add") :fail}))
        [db' act] (held db)
        released (d/db-with db' (task/execution-tx ["demo/add"] :running))
        [sp & more] (r/reconcile released)]
    (is (nil? more))
    (is (= (mspawn "add" 2) sp) "a new attempt, on the task's own branch")
    (is (= (get-in (task/attempt db "demo/add#1") [:seon.attempt/env :branch]) (get-in sp [:env :branch])))
    (testing "the resumed branch takes the task as main holds it: its failure and evidence"
      (let [on-branch (d/db-with db (task/onto-tx released db "demo/add"))
            tk (task/task on-branch "demo/add")]
        (is (= (:problem act) (:problem (:seon.task/evidence tk))))
        (is (= [(t "add")] (:red (:seon.task/evidence tk))))
        (is (= "demo/add#1" (:attempt (:seon.task/failure tk))))))
    (is (not= :failed (r/task-state (r/index released) "demo/add")) "released, it is no longer failed")))

(deftest an-idle-system-does-nothing-after-a-refusal
  ;; the reconciler proc's own steps: the refusal's hold is one write; once it is committed
  ;; and answered nothing is left to do (quiet, naming the held task as a root cause); a
  ;; root-only commit after it asks nothing
  (let [db (mdb [(decl "add") (decl "twice" :needs ["add"])]
                :extra (attempt "add" 1 :ended :outcome :cap :results {(t "add") :fail}))
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
    (is (= [{:task "demo/add" :cause "attempt demo/add#1 ended cap: proof-red demo-test/add-test" :attempt "demo/add#1"
             :blocks ["demo/add-merge" "demo/twice" "demo/twice-merge"]}]
           (:stuck quiet)) "quiet, naming the held task and what it blocks")
    (is (not-any? #(= :spawn (get-in % [:req :op])) (reqs outs2)) "nothing starts")
    (let [[db3 ops3] (commit db2 [{:seon.root/id :root :seon.root/quiescent quiet}])
          [_ outs3] (main st db3 ops3)]
      (is (empty? (reqs outs3)) "a commit of root's own report asks nothing"))))

(deftest stuck-names-cancelled-and-held-roots
  (let [db (db-of diamond :extra (concat (merged "a") (task/execution-tx ["demo/b"] :cancelled) (task/execution-tx ["demo/c"] :held)))]
    (is (= [{:task "demo/b" :cause "cancelled" :blocks ["demo/d"]} {:task "demo/c" :cause "held" :blocks ["demo/d"]}]
           (r/stuck (r/index db))))))

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
                :extra (concat (attempt "add" 1 :closed)
                               [{:seon.task/id "demo/add" :seon.task/execution :held :seon.task/failure {:reason "capped" :attempt "demo/add#1"}}]))
        commit (fn [db tx] (let [rep (d/with db tx)] [(:db-after rep) (pdb/report->tx rep)]))
        step (fn [st db ops] (let [[st outs] (r/reconciler-proc st :main {:t (:max-tx db) :ops ops :db db :ns (profile/now-ns)})] [st (:to-api outs)]))
        report-in (fn [outs] (some #(get-in % [:req :req :tx-data 0 :seon.root/quiescent]) outs))
        [st outs] (step (r/reconciler-proc {:boot "boot-1"}) db [])
        report (report-in outs)
        blocked ["demo/add-merge" "demo/twice" "demo/twice-merge"]]
    (testing "nothing to start, nothing in flight: one positive report, naming the held root and what it blocks"
      (is (empty? (:flights st)))
      (is (= {:t (:max-tx db) :stuck [{:task "demo/add" :cause "capped" :attempt "demo/add#1" :blocks blocked}]} report))
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

(deftest a-refused-spawn-writes-a-refused-attempt
  ;; root refuses the spawn (the branch is held, or too many live agents): the refusal is
  ;; the attempt, written on main, and the next pass starts the task again
  (let [db (db-of [(decl "a")])
        [st outs] (r/reconciler-proc (r/reconciler-proc {:boot "boot-1"}) :main {:t (:max-tx db) :ops [] :db db :ns (profile/now-ns)})
        asked (first (filter #(= :spawn (get-in % [:req :op])) (:to-api outs)))
        refusal (contract/error :seon.root/refused 'seon.root/spawn! held-why {:branch :demo/a :agent :a9})
        [_ outs2] (r/reconciler-proc st :answers {:tag (:tag asked) :r refusal})
        written (vec (mapcat #(get-in % [:req :req :tx-data]) (filter #(= :write (get-in % [:req :op])) (:to-api outs2))))
        attempt (first (filter #(= "demo/a#1" (:seon.attempt/id %)) written))]
    (is (some? asked) "the first pass asks for the spawn")
    (is (= :refused (:seon.attempt/status attempt)) "the refusal is written as the attempt")
    (is (= held-why (:seon.attempt/reason attempt)) "with root's reason")
    (is (= [(spawn "a" 2)] (r/reconcile (d/db-with db written))) "the next pass starts the task again")))

(deftest a-refused-task-asks-again-when-main-moves
  ;; if nothing has changed, asking again is refused again: neither the refusal's answer nor
  ;; its own commit starts the task; other work on main lets it ask again, as its next attempt
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
    (is (= [] (spawns outs4)) "nor does the refusal's own commit")
    (is (= [{:task "demo/a" :cause held-why :attempt "demo/a#1" :blocks []}] (get-in st [:quiescent :stuck])) "quiet, naming it")
    (is (= ["demo/a"] (spawns outs5)) "other work on main: it asks again")))

(deftest quiescence-names-a-task-that-cannot-start
  ;; a refused attempt is a root cause at quiescence, naming what it blocks
  (let [db (db-of [(decl "a") (decl "b" :needs ["a"])]
                  :extra (concat (attempt "a" 1 :refused) [{:seon.attempt/id "demo/a#1" :seon.attempt/reason held-why}]))]
    (is (= [{:task "demo/a" :cause held-why :attempt "demo/a#1" :blocks ["demo/b"]}] (r/stuck (r/index db))))))

(deftest a-task-marked-failed-is-held-for-root
  ;; owner, 2026-10-22: a failure is task data and root reacts to it; D2(a): held for root,
  ;; no automatic retry. my.task/fail! writes the failure on the branch; root's judge
  ;; carries this attempt's onto main with the task held, and the attempt closes
  (let [main (db-of [(decl "a")] :extra (attempt "a" 1 :running))
        f {:reason "the premise is refuted" :counterexample "(= 1 2)" :attempt "demo/a#1"}
        branch (d/db-with main [{:seon.task/id "demo/a" :seon.task/failure f}])
        judge #(#'r/judge-tx {:db main :boot "boot-1" :rid 0 :flights {} :pending {}} {:act {:attempt "demo/a#1" :task "demo/a"}} {} {:db % :base main} #{})
        judged (d/db-with main (judge branch))
        idx (r/index judged)]
    (is (= :held (:seon.task/execution (task/task judged "demo/a"))) "held for root, without a merge")
    (is (= f (:seon.task/failure (task/task judged "demo/a"))) "the failure, on main")
    (is (= :failed (r/task-state idx "demo/a")))
    (is (= [{:op :close :attempt "demo/a#1"}] (r/reconcile judged)) "the attempt ends; nothing refines or retries it")
    (let [[row & more] (get-in (problems/report judged) [:classes :failed :rows])]
      (is (nil? more))
      (is (= {:task "demo/a" :reason "the premise is refuted" :counterexample "(= 1 2)" :open? true}
             (select-keys row [:task :reason :counterexample :open?])) "root is told: a :failed row, the one producer of what is wrong"))
    (let [released (d/db-with judged (task/execution-tx ["demo/a"] :running))]
      (is (not= :failed (r/task-state (r/index released) "demo/a")) "a release is root's answer")
      (is (false? (:open? (first (get-in (problems/report released) [:classes :failed :rows])))) "and the row closes"))
    (is (nil? (:seon.task/failure (task/task (d/db-with main (judge (d/db-with main [{:seon.task/id "demo/a" :seon.task/failure (assoc f :attempt "demo/a#0")}])))
                                            "demo/a")))
        "an earlier attempt's failure on a reused branch is not this attempt's")))

(deftest a-branch-carries-every-task-it-changed-and-records-a-two-sided-edit
  ;; owner, 2026-10-22 (D4): when a branch's attempt ends, a task version newer than main's
  ;; overwrites it; one edited on both sides differently is a conflict for root
  (let [base (db-of [(decl "a") (decl "b") (decl "c")] :extra (attempt "a" 1 :running))
        branch (d/db-with base [{:seon.task/id "demo/b" :seon.task/plan "b, as a's agent learned it"}
                                {:seon.task/id "demo/c" :seon.task/plan "c, the branch's"}])
        main (d/db-with base [{:seon.task/id "demo/c" :seon.task/plan "c, main's"}])
        judged (d/db-with main (#'r/judge-tx {:db main :boot "boot-1" :rid 0 :flights {} :pending {}}
                                             {:act {:attempt "demo/a#1" :task "demo/a"}} {} {:db branch :base base} #{}))
        conflict {:task "demo/c" :attr :seon.task/plan :base "Make c true." :ours "c, the branch's" :theirs "c, main's"}]
    (is (= "b, as a's agent learned it" (:seon.task/plan (task/task judged "demo/b"))) "another task's edit reaches main at the attempt's end")
    (is (= "Make a true." (:seon.task/plan (task/task judged "demo/a"))) "an unchanged task stays as main holds it")
    (is (= "c, main's" (:seon.task/plan (task/task judged "demo/c"))) "a two-sided edit is never resolved here")
    (is (= [conflict] (:seon.attempt/task-conflicts (d/pull judged [:seon.attempt/task-conflicts] [:seon.attempt/id "demo/a#1"]))))
    (is (= [(assoc conflict :from "demo/a#1")] (map :evidence (get-in (problems/report judged) [:classes :task-conflict :rows])))
        "root sees it among the problems")
    (testing "main's task plans never put a merge behind: merging merges the program, no task data"
      (is (empty? (:theirs (pm/three-way (pm/program base #{}) (pm/program branch #{}) (pm/program main #{}))))))))
