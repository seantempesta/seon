(ns seon.reconcile-test
  "(reconcile db) answers exactly the expected actions for a table of task graphs: needs
  DAGs, max-running, held and cancelled tasks, environment changes, a previous root's
  attempts, ended attempts judged by the done rule, dead agents."
  (:require [clojure.test :refer [deftest is are testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.task :as task]
            [seon.reconcile :as r]
            [seon.template :as tpl] [seon.problems :as problems] [seon.sock :as sock] [clojure.edn]
            [clojure.string :as str] [clojure.set] [seon.config :as cfg]))

(defn- empty-db [] @(second (pdb/test-branch!)))

(defn- decl [nm & {:keys [needs proofs model]}]
  (cond-> {:seon.task/ns 'demo :seon.task/name nm :seon.task/plan (str "Make " nm " true.")
           :seon.task/proofs (or proofs #{(symbol "demo-test" (str nm "-test"))})}
    needs (assoc :seon.task/needs (set (map #(str "demo/" %) needs)))
    model (assoc :seon.task/model model)))

(def diamond [(decl "a") (decl "b" :needs ["a"]) (decl "c" :needs ["a"]) (decl "d" :needs ["b" "c"])])

(defn- attempt
  "An attempt fact of task nm: status, plus members (results as {test status}; conflicts
  as :seon.merge/conflict values; unmerged as printed keys)."
  [nm n status & {:keys [boot model results outcome conflicts unmerged changed] :or {boot "boot-1"}}]
  (let [id (str "demo/" nm) aid (str id "#" n)]
    (into (task/attempt-start-tx aid id boot (keyword (str "a-" nm n))
                                 {:branch (task/default-branch id) :model model :max-turns r/default-max-turns} 1000)
          [(cond-> {:seon.attempt/id aid :seon.attempt/status status}
             results (assoc :seon.attempt/results (vec (for [[t s] results] {:seon.proof/test t :seon.proof/status s})))
             outcome (assoc :seon.attempt/outcome outcome)
             conflicts (assoc :seon.attempt/conflicts (task/conflict-members conflicts))
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
    (is (= [["demo/b#1" :cancelled]] (r/to-kill (r/index db))))
    ;; killed: nothing restarts it, and d stays blocked once c merges
    (let [db (d/db-with db (concat (task/status-tx db "demo/b#1" :running :killed {})
                                   (task/status-tx db "demo/c#1" :running :merged {})))]
      (is (= [] (r/reconcile db)))
      (is (= #{"demo/b"} (r/blocked-by (r/index db) "demo/d")))
      (is (= [] (r/should-run (r/index db)))))))

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
    ;; its proof red: a refinement chain attaches (the budget has room)
    (attempt "a" 1 :ended :outcome :cap :results {(t "a") :fail})
    [{:op :attach :attempt "demo/a#1" :task "demo/a" :problem "attempt demo/a#1 ended cap: proof-red demo-test/a-test"}]
    ;; its proof never ran
    (attempt "a" 1 :ended :outcome :green :results {})
    [{:op :attach :attempt "demo/a#1" :task "demo/a" :problem "attempt demo/a#1 ended green: proof-red demo-test/a-test"}]
    ;; b needs a, so b carries a's proof (accumulated along :needs): red there is b's own red
    (concat (merged "a") (attempt "b" 1 :ended :outcome :green :results {(t "a") :fail (t "b") :pass}))
    [{:op :attach :attempt "demo/b#1" :task "demo/b" :problem "attempt demo/b#1 ended green: proof-red demo-test/a-test"}
     {:op :spawn :task "demo/c" :attempt "demo/c#1"
      :env {:branch :task-demo-c :model nil :max-turns r/default-max-turns}}]
    ;; a stray red: a red test that is no task's proof
    (attempt "a" 1 :ended :outcome :green :results {(t "a") :pass 'demo-test/other :error})
    [{:op :attach :attempt "demo/a#1" :task "demo/a" :problem "attempt demo/a#1 ended green: stray-red demo-test/other"}]
    ;; its agent died
    (attempt "a" 1 :dead)
    [{:op :attach :attempt "demo/a#1" :task "demo/a" :problem "its agent died"}]
    ;; budget spent (declared 0): bail, which makes the task stuck
    (concat (attempt "a" 1 :ended :outcome :cap :results {(t "a") :fail}) [{:seon.task/id "demo/a" :seon.task/budget 0}])
    [{:op :bail :attempt "demo/a#1" :task "demo/a" :problem "attempt demo/a#1 ended cap: proof-red demo-test/a-test"}]))

(deftest an-ended-attempt-of-a-cancelled-task-is-closed
  (is (= [{:op :close :attempt "demo/a#1"}]
         (r/reconcile (db-of diamond :extra (concat (attempt "a" 1 :ended :outcome :green :results {(t "a") :pass})
                                                    (task/execution-tx ["demo/a"] :cancelled)))))))

(deftest done-refusals-table
  (are [in expected] (= expected (mapv (juxt :refusal :symbol) (r/done-refusals in)))
    {:proofs #{'p/a} :results {'p/a :pass} :main-green #{} :allowed-red #{} :uncontracted #{}} []
    {:proofs #{} :results {} :main-green #{} :allowed-red #{} :uncontracted #{}} [[:no-proofs nil]]
    {:proofs #{'p/a} :results {'p/a :error} :main-green #{} :allowed-red #{} :uncontracted #{}} [[:proof-red 'p/a]]
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
    (is (= :attach (:op (first (r/reconcile (db-of two :extra (concat merged-x (y 150))))))))))

(deftest contracts-are-checked
  ;; dev/test.clj arms every contract before the tests run: a call outside its contract is
  ;; refused at entry with the one error map
  (when (not= "off" (System/getenv "PROTO_GUARANTEES"))
    (is (= :seon.instrument/contract-error
           (:seon.error/type (try (task/task-id "not-a-symbol" "x") (catch Exception e (ex-data e))))))))

;; ---- true branches: merge tasks, unmerged entries, behind, a failed merge attaches tasks (M1) ----

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
                   (r/done-refusals (r/done-input (r/index db) "demo/b-merge" {(t "b") :pass (t "a") :fail} #{} [] nil))))))))

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
  ;; a train of one: no other merge attempt runs
  (is (= [{:op :merge-train :attempt "demo/a-merge#1" :members [{:attempt "demo/a-merge#1" :task "demo/a-merge"}]}]
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
  ;; DECISIONS: merge trains stay off (one merge at a time) until the train's combined test run
  ;; and the par-200 hang are fixed; the dial in force is a fact on main a person can read
  (let [db (d/db-with (empty-db) (into (task/boot-tx "boot-1" 4) (cfg/facts cfg/config {})))]
    (is (= 1 r/default-max-merging))
    (is (= {:boot "boot-1" :max-running 4 :max-merging 1} (task/root db)) "both dials are recorded at boot")))

(deftest two-ready-disjoint-merges-run-one-at-a-time-by-default
  ;; x and y change disjoint definitions: a train could take both, but the dial is 1
  (let [two [(decl "x") (decl "y")]
        extra (concat (attempt "x" 1 :done :changed #{"[:var app.m/x]"}) (attempt "y" 1 :done :changed #{"[:var app.m/y]"}))]
    (is (= ["demo/x-merge"] (map :task (filter #(= :spawn (:op %)) (r/reconcile (mdb two :extra extra))))))
    (testing "a train is turned on by writing the fact"
      (is (= ["demo/x-merge" "demo/y-merge"]
             (map :task (filter #(= :spawn (:op %)) (r/reconcile (mdb two :extra (concat extra [{:seon.root/id :root :seon.config.merge/max-running 8}]))))))))))

(deftest unmerged-refuses-done
  (is (= [{:op :attach :attempt "demo/a#1" :task "demo/a"
           :problem "attempt demo/a#1 ended green: unmerged [:var app/f]"}]
         (r/reconcile (db-of diamond :extra (attempt "a" 1 :ended :outcome :green :results {(t "a") :pass}
                                                     :unmerged ["[:var app/f]"]))))))

(deftest behind-merge-gets-a-new-attempt
  (testing "root's merge found main moved: the merge task's next attempt is a new agent; no catch-up"
    (let [db (mdb [(decl "a")] :extra (concat (attempt "a" 1 :done) (attempt "a-merge" 1 :behind :outcome :green)))]
      (is (= [(mspawn "a-merge" 2)] (r/reconcile db))))))

(deftest design-may-leave-its-proofs-red
  (are [in expected] (= expected (mapv :refusal (r/done-refusals in)))
    {:kind :design :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{}} []
    {:kind :design :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{'app/f}} [:contract-gap]
    {:kind :work :proofs #{'p/a} :results {'p/a :fail} :main-green #{} :allowed-red #{} :uncontracted #{}} [:proof-red]))

(deftest failed-merge-attaches-four-tasks
  (let [db (mdb [(decl "a") (decl "b" :needs ["a"])]
                :extra (concat (attempt "a" 1 :done)
                               (attempt "a-merge" 1 :ended :outcome :cap :results {(t "a") :fail}
                                        :conflicts [conflict-f] :unmerged ["[:var app/f]"])
                               ;; the plan as the failed merge's agent improved it (root's judge carried it to main)
                               (task/plan-tx "demo/a-merge" "Make a true. Learned: main's f now decrements.")))
        acts (r/reconcile db)]
    (is (= [{:op :attach :attempt "demo/a-merge#1" :task "demo/a-merge"
             :problem "attempt demo/a-merge#1 ended cap: proof-red demo-test/a-test; unmerged [:var app/f]"}] acts))
    (let [db' (d/db-with db (tpl/attach-tx db (first acts)))
          ids ["demo/a-r1-design" "demo/a-r1-implement" "demo/a-r1-test" "demo/a-r1-merge"]
          ts (mapv #(task/task db' %) ids)]
      (testing "exactly four new tasks, chained design -> implement -> test -> merge, released, on one fresh branch"
        (is (= 4 (- (count (task/tasks db')) (count (task/tasks db)))))
        (is (every? some? ts))
        (is (= [:design :implement :test :merge] (map :seon.task/kind ts)))
        (is (= [#{} #{"demo/a-r1-design"} #{"demo/a-r1-implement"} #{"demo/a-r1-test"}] (map :seon.task/needs ts)))
        (is (every? #(= :running (:seon.task/execution %)) ts))
        (is (= 1 (count (set (map :seon.task/branch ts)))))
        (is (not= :task-demo-a (:seon.task/branch (first ts)))))
      (testing "each carries the accumulated plan and proofs, a superset of the failed merge's, and the measured evidence"
        (let [failed (task/task db "demo/a-merge")]
          (is (every? #(clojure.set/subset? (:proofs (:seon.task/accumulated failed)) (:proofs (:seon.task/accumulated %))) ts))
          (is (every? #(str/includes? (:plan (:seon.task/accumulated %)) "Learned: main's f now decrements.") ts))
          (is (every? #(str/includes? (:plan (:seon.task/accumulated %)) "Make a true.") ts)))
        (is (every? #(= {:from "demo/a-merge#1" :conflicts [conflict-f] :unmerged ["[:var app/f]"] :red [(t "a")]
                         :problem "attempt demo/a-merge#1 ended cap: proof-red demo-test/a-test; unmerged [:var app/f]"}
                        (:seon.task/evidence %)) ts)))
      (testing "the failed merge is cancelled and closed; its dependent needs the new merge"
        (is (= :cancelled (:seon.task/execution (task/task db' "demo/a-merge"))))
        (is (= :closed (:seon.attempt/status (task/attempt db' "demo/a-merge#1"))))
        (is (= #{"demo/a-r1-merge"} (:seon.task/needs (task/task db' "demo/b")))))
      (testing "the next pass starts only the design task, as a new attempt; nothing relaunches the old attempts"
        (is (= [{:op :spawn :task "demo/a-r1-design" :attempt "demo/a-r1-design#1"
                 :env {:branch (:seon.task/branch (first ts)) :model nil :max-turns r/default-max-turns}}]
               (r/reconcile db')))))))

(deftest a-capped-attempt-attaches-a-refinement-not-a-hold
  ;; inv1's repro: add always wrong, twice needing it. add's failure cancels add and its
  ;; merge (the rest of its branch's chain) and twice needs the new merge
  (let [db (mdb [(decl "add") (decl "twice" :needs ["add"])]
                :extra (attempt "add" 1 :ended :outcome :cap :results {(t "add") :fail}))
        [act :as acts] (r/reconcile db)
        db' (d/db-with db (tpl/attach-tx db act))]
    (is (= [:attach] (map :op acts)))
    (is (= [:cancelled :cancelled] (map #(:seon.task/execution (task/task db' %)) ["demo/add" "demo/add-merge"])))
    (is (= #{"demo/add-r1-merge"} (:seon.task/needs (task/task db' "demo/twice"))))
    (is (= 1 (:seon.task/generation (task/task db' "demo/add-r1-design"))))
    (is (= "attempt demo/add#1 ended cap: proof-red demo-test/add-test" (:problem (:seon.task/evidence (task/task db' "demo/add-r1-design")))))
    (is (= [{:op :spawn :task "demo/add-r1-design" :attempt "demo/add-r1-design#1"
             :env {:branch :task-demo-add-r1 :model nil :max-turns r/default-max-turns}}]
           (r/reconcile db')))
    (testing "a chain task's own failure refines from the same stem, one generation on"
      (let [db2 (d/db-with db' (concat (attempt "add-r1-design" 1 :done)
                                       (attempt "add-r1-implement" 1 :ended :outcome :cap :results {(t "add") :fail})))
            [act2] (r/reconcile db2)
            db3 (d/db-with db2 (tpl/attach-tx db2 act2))]
        (is (= :attach (:op act2)))
        (is (= #{"demo/add-r2-merge"} (:seon.task/needs (task/task db3 "demo/twice"))))
        (is (= [:cancelled :cancelled :cancelled]
               (map #(:seon.task/execution (task/task db3 %)) ["demo/add-r1-implement" "demo/add-r1-test" "demo/add-r1-merge"])))
        (is (= 2 (:seon.task/generation (task/task db3 "demo/add-r2-design"))))))))

(deftest a-spent-budget-is-a-stuck-fact-naming-what-it-blocks
  (let [db (mdb [(decl "add") (decl "twice" :needs ["add"])]
                :extra (concat [{:seon.task/id "demo/add" :seon.task/generation 2}]
                               (attempt "add" 1 :ended :outcome :cap :results {(t "add") :fail})))
        idx (r/index db)
        [act :as acts] (r/actions idx)]
    (is (= [{:op :bail :attempt "demo/add#1" :task "demo/add" :problem "attempt demo/add#1 ended cap: proof-red demo-test/add-test"}] acts))
    (let [db' (d/db-with db (concat (task/status-tx db "demo/add#1" :ended :bailed {})
                                    (r/stuck-tx idx "demo/add#1" "demo/add" (:problem act))))
          idx' (r/index db')
          blocked ["demo/add-merge" "demo/twice" "demo/twice-merge"]]
      (testing "the release stays the owner's; the stuck fact names the cause and what it blocks"
        (is (= :running (:seon.task/execution (task/task db' "demo/add"))))
        (is (= {:attempt "demo/add#1" :cause (:problem act) :blocks blocked} (:seon.task/stuck (task/task db' "demo/add")))))
      (testing "nothing is relaunched, nothing waits unexplained"
        (is (= [] (r/actions idx')))
        (is (= [{:task "demo/add" :cause (:problem act) :attempt "demo/add#1" :blocks blocked}] (r/stuck idx'))))
      (testing "the problems read lists it, open, with its evidence"
        (is (= [{:task "demo/add" :attempt "demo/add#1" :open? true :blocks blocked}]
               (mapv #(select-keys % [:task :attempt :open? :blocks]) (get-in (problems/report db') [:classes :stuck :rows]))))))))

(deftest stuck-names-cancelled-and-held-roots
  (let [db (db-of diamond :extra (concat (merged "a") (task/execution-tx ["demo/b"] :cancelled) (task/execution-tx ["demo/c"] :held)))]
    (is (= [{:task "demo/b" :cause "cancelled" :blocks ["demo/d"]} {:task "demo/c" :cause "held" :blocks ["demo/d"]}]
           (r/stuck (r/index db))))))

(deftest hairy-rule
  (let [t {:seon.task/accumulated {:plan "Use `app/g` and `app/f`." :proofs #{'app.proofs/a-test}}}
        c (fn [k kind] {:key [:var k] :kind kind})
        dials {:max-conflicts 3 :max-merge-turns 2}]
    (are [conflicts merge-turns rule] (= rule (:rule (r/hairy (merge dials {:conflicts conflicts :task t :merge-turns merge-turns}))))
      [] 0 nil
      [(c 'app/f :both-modified)] 0 nil
      [(c 'app/f :both-modified) (c 'app/h :both-modified) (c 'app/i :both-added)] 0 nil
      [(c 'app/f :both-modified) (c 'app/h :both-modified) (c 'app/i :both-added) (c 'app/j :both-added)] 0 :too-many-conflicts
      [(c 'app/g :deleted-by-them)] 0 :deleted-by-them-referenced
      [(c 'app/z :deleted-by-them)] 0 nil
      [(c 'app.proofs/a-test :deleted-by-them)] 0 :deleted-by-them-referenced
      [(c 'app/f :both-modified)] 3 :merge-turns)))

(deftest quiescence-is-reported-when-nothing-can-move
  ;; the reconciler proc's own steps over real commits: a stuck task and its dependents, nothing live
  (let [db (mdb [(decl "add") (decl "twice" :needs ["add"])]
                :extra (concat [{:seon.task/id "demo/add" :seon.task/generation 2}]
                               (attempt "add" 1 :bailed)
                               (r/stuck-tx (r/index (mdb [(decl "add") (decl "twice" :needs ["add"])])) "demo/add#1" "demo/add" "capped")))
        commit (fn [db tx] (let [rep (d/with db tx)] [(:db-after rep) (pdb/report->tx rep)]))
        step (fn [st db ops] (let [[st outs] (r/reconciler-proc st :main {:t (:max-tx db) :ops ops :db db :ns (sock/now-ns)})] [st (:to-api outs)]))
        report-in (fn [outs] (some #(some-> (get-in % [:req :req :tx-data 0 :seon.root/quiescent]) clojure.edn/read-string) outs))
        [st outs] (step (r/reconciler-proc {:boot "boot-1"}) db [])
        report (report-in outs)
        blocked ["demo/add-merge" "demo/twice" "demo/twice-merge"]]
    (testing "nothing to start, nothing in flight: one positive report, naming the stuck root and what it blocks"
      (is (empty? (:flights st)))
      (is (= {:t (:max-tx db) :stuck [{:task "demo/add" :cause "capped" :attempt "demo/add#1" :blocks blocked}]} report))
      (is (:quiet st)))
    (let [[db2 ops2] (commit db [{:seon.root/id :root :seon.root/quiescent (pr-str report)}])
          [st2 outs2] (step st db2 ops2)]
      (testing "the report's own commit (root only) makes no second report; main holds it"
        (is (:quiet st2))
        (is (nil? (report-in outs2)))
        (is (= report (r/quiescent db2))))
      (testing "a commit touching tasks ends the spell: nothing can move still, so it is reported again at the new t"
        (let [[db3 ops3] (commit db2 [{:seon.task/id "demo/twice" :seon.task/plan "Make twice true, again."}])
              [_ outs3] (step st2 db3 ops3)]
          (is (= (:max-tx db3) (:t (report-in outs3))))))
      (testing "work to start is not quiet: unsticking add starts it, and no report goes out"
        (let [[db4 ops4] (commit db2 [[:db/retract [:seon.task/id "demo/add"] :seon.task/stuck
                                       (:seon.task/stuck (d/pull db2 [:seon.task/stuck] [:seon.task/id "demo/add"]))]])
              [st4 outs4] (step st2 db4 ops4)]
          (is (= ["demo/add"] (keep #(get-in % [:req :opts :task]) outs4)))
          (is (not (:quiet st4)))
          (is (nil? (report-in outs4))))))))
