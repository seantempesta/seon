(ns seon.reconcile-test
  "(reconcile db) answers exactly the expected actions for a table of task graphs: needs
  DAGs, max-running, held and cancelled tasks, environment changes, a previous root's
  attempts, ended attempts judged by the done rule, dead agents."
  (:require [clojure.test :refer [deftest is are testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.task :as task]
            [seon.reconcile :as r]
            [seon.template :as tpl]
            [clojure.string :as str] [clojure.set]))

(defn- empty-db []
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (-> @(d/connect cfg) (d/db-with pdb/schema) )))

(defn- decl [nm & {:keys [needs proofs model]}]
  (cond-> {:seon.task/ns 'demo :seon.task/name nm :seon.task/plan (str "Make " nm " true.")
           :seon.task/proofs (or proofs #{(symbol "demo-test" (str nm "-test"))})}
    needs (assoc :seon.task/needs (set (map #(str "demo/" %) needs)))
    model (assoc :seon.task/model model)))

(def diamond [(decl "a") (decl "b" :needs ["a"]) (decl "c" :needs ["a"]) (decl "d" :needs ["b" "c"])])

(defn- attempt
  "An attempt fact of task nm: status, plus members (results as {test status}; conflicts
  as :seon.merge/conflict values; unmerged as printed keys)."
  [nm n status & {:keys [boot model results outcome conflicts unmerged] :or {boot "boot-1"}}]
  (let [id (str "demo/" nm) aid (str id "#" n)]
    (into (task/attempt-start-tx aid id boot (keyword (str "a-" nm n))
                                 {:branch (task/default-branch id) :model model :max-turns r/default-max-turns} 1000)
          [(cond-> {:seon.attempt/id aid :seon.attempt/status status}
             results (assoc :seon.attempt/results (vec (for [[t s] results] {:seon.proof/test t :seon.proof/status s})))
             outcome (assoc :seon.attempt/outcome outcome)
             conflicts (assoc :seon.attempt/conflicts (task/conflict-members conflicts))
             unmerged (assoc :seon.attempt/unmerged unmerged))])))

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
    (is (= #{"demo/b" "demo/c"} (r/blocked-by db "demo/d")))
    (is (= [["demo/b#1" :cancelled]] (r/to-kill db)))
    ;; killed: nothing restarts it, and d stays blocked once c merges
    (let [db (d/db-with db (concat (task/status-tx db "demo/b#1" :running :killed {})
                                   (task/status-tx db "demo/c#1" :running :merged {})))]
      (is (= [] (r/reconcile db)))
      (is (= #{"demo/b"} (r/blocked-by db "demo/d")))
      (is (= [] (r/should-run db))))))

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
    (is (= #{} (r/running db)))))

(deftest ended-attempts-are-judged-by-the-done-rule
  (are [extra expected] (= expected (r/reconcile (db-of diamond :extra extra)))
    ;; green: a work task is accepted on its branch
    (attempt "a" 1 :ended :outcome :green :results {(t "a") :pass (t "b") :fail})
    [{:op :accept :attempt "demo/a#1" :task "demo/a"}]
    ;; its proof red: bail to root
    (attempt "a" 1 :ended :outcome :cap :results {(t "a") :fail})
    [{:op :bail :attempt "demo/a#1" :task "demo/a" :problem "attempt demo/a#1 ended cap: proof-red demo-test/a-test"}]
    ;; its proof never ran
    (attempt "a" 1 :ended :outcome :green :results {})
    [{:op :bail :attempt "demo/a#1" :task "demo/a" :problem "attempt demo/a#1 ended green: proof-red demo-test/a-test"}]
    ;; b needs a, so b carries a's proof (accumulated along :needs): red there is b's own red
    (concat (merged "a") (attempt "b" 1 :ended :outcome :green :results {(t "a") :fail (t "b") :pass}))
    [{:op :bail :attempt "demo/b#1" :task "demo/b" :problem "attempt demo/b#1 ended green: proof-red demo-test/a-test"}
     {:op :spawn :task "demo/c" :attempt "demo/c#1"
      :env {:branch :task-demo-c :model nil :max-turns r/default-max-turns}}]
    ;; a stray red: a red test that is no task's proof
    (attempt "a" 1 :ended :outcome :green :results {(t "a") :pass 'demo-test/other :error})
    [{:op :bail :attempt "demo/a#1" :task "demo/a" :problem "attempt demo/a#1 ended green: stray-red demo-test/other"}]
    ;; its agent died
    (attempt "a" 1 :dead)
    [{:op :bail :attempt "demo/a#1" :task "demo/a" :problem "its agent died"}]))

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
    (is (= :bail (:op (first (r/reconcile (db-of two :extra (concat merged-x (y 150))))))))))

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
                   (r/done-refusals (r/done-input (r/view db) "demo/b-merge" {(t "b") :pass (t "a") :fail} #{} [] nil))))))))

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
          (is (= #{"demo/a-merge"} (r/blocked-by db "demo/b"))))))))

(deftest green-merge-attempt-merges
  (is (= [{:op :merge :attempt "demo/a-merge#1" :task "demo/a-merge"}]
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

(deftest unmerged-refuses-done
  (is (= [{:op :bail :attempt "demo/a#1" :task "demo/a"
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
    (is (= [{:op :attach :attempt "demo/a-merge#1" :task "demo/a-merge"}] acts))
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
        (is (every? #(= {:from "demo/a-merge#1" :conflicts [conflict-f] :unmerged ["[:var app/f]"] :red [(t "a")]}
                        (:seon.task/evidence %)) ts)))
      (testing "the failed merge is cancelled and closed; its dependent needs the new merge"
        (is (= :cancelled (:seon.task/execution (task/task db' "demo/a-merge"))))
        (is (= :closed (:seon.attempt/status (task/attempt db' "demo/a-merge#1"))))
        (is (= #{"demo/a-r1-merge"} (:seon.task/needs (task/task db' "demo/b")))))
      (testing "the next pass starts only the design task, as a new attempt; nothing relaunches the old attempts"
        (is (= [{:op :spawn :task "demo/a-r1-design" :attempt "demo/a-r1-design#1"
                 :env {:branch (:seon.task/branch (first ts)) :model nil :max-turns r/default-max-turns}}]
               (r/reconcile db')))))))

(deftest attach-stops-at-max-attach
  (let [db (mdb [(decl "a")] :extra (concat (attempt "a" 1 :done)
                                             [{:seon.task/id "demo/a-merge" :seon.task/generation 2}]
                                             (attempt "a-merge" 1 :ended :outcome :cap :results {(t "a") :fail})))]
    (is (= [:bail] (map :op (r/reconcile db))))))

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
