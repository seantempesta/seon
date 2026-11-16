(ns r3.merge-queue-test
  "The merge queue's rules in seon.reconcile/actions, over a small main: disjoint merge tasks work
  together; overlapping ones serialize; unknown changes run alone; the queue closes to new merge tasks when
  one is judged; each ready one merges alone (the writer's one :merge) once no merge task is working."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [seon.reconcile :as r] [seon.task :as task]
            [seon.template :as tpl] [seon.db :as pdb] [datahike.api :as d]))

(def boot "b")

(defn- main
  "(f db) on a main value (an empty db with the schema, in memory: no store branch) holding work tasks (names) each done with
  the changed keys given, their merges released."
  {:malli/schema [:=> [:cat [:sequential [:tuple :string [:maybe [:sequential :string]]]] fn?] :any]}
  [changes f]
  (let [ds (tpl/with-merge (vec (for [[nm _] changes] {:seon.task/ns 't :seon.task/name nm :seon.task/plan "p"
                                                         :seon.task/proofs #{(symbol "t" (str nm "-test"))}})))
        with (fn [db tx] (d/db-with db (vec tx)))
        db (with (pdb/empty-db false) (into (task/boot-tx boot "sean") (seon.task/config-tx {:seon.config.agent/max-running 16 :seon.config.merge/max-running 8} {})))
        tasks (task/create-tx db ds)
        ;; the entities the tasks name by identity (a merge step's var, the sections its plan cites), which the test store's seed held: stated once each
        db (with db (distinct (for [x (tree-seq coll? seq tasks) :when (and (vector? x) (= 2 (count x)) (#{:var/name :seon.doc/address} (first x)))] {(first x) (second x)})))
        db (with db tasks)
        db (with db (task/execution-tx (mapv #(task/task-id 't (:seon.task/name %)) ds) :running))
        db (reduce (fn [db [nm ks]] (let [id (str "t/" nm)]
                                      (with db (into (ts/work-tx id :done {:boot boot :agent :a :max-turns 8})
                                                     [(cond-> {:seon.task/id id} ks (assoc :seon.task/changed (set ks)))]))))
                   db changes)]
    (f db)))

(defn- spawned
  "The tasks acts spawn."
  {:malli/schema [:=> [:cat [:sequential :map]] [:set :string]]}
  [acts] (set (for [a acts :when (= :spawn (:op a))] (:task a))))

(defn- start-merge!
  "db with merge task id working, ended at outcome, on its work's branch."
  {:malli/schema [:=> [:cat :seon.host/db :string :keyword] :seon.host/db]}
  [db id outcome]
  (d/db-with db (ts/work-tx id outcome {:boot boot :agent :m :branch (task/default-branch (subs id 0 (- (count id) 6))) :max-turns 8})))

;; Estimate (in memory, no test branch): 6 tasks created and 3 done ~1.5M + one index and actions ~0.7M ~ 3,200,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3200000}} disjoint-merges-run-together-overlapping-serialize
  (main [["a" ["[:var x/f]"]] ["b" ["[:var x/g]"]] ["c" ["[:var x/f]" "[:var x/h]"]]] (fn [db] (is (= #{"t/a-merge" "t/b-merge"} (spawned (r/actions (r/index db)))) "a and b are disjoint; c overlaps a and waits"))))

;; Estimate (in memory, no test branch): 4 tasks created and 2 done ~1.2M + one index and actions ~0.7M ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} unknown-changes-run-alone
  (main [["a" nil] ["b" ["[:var x/g]"]]] (fn [db] (is (= 1 (count (spawned (r/actions (r/index db))))) "a merge whose changes no run measured runs alone"))))

;; Estimate (in memory, no test branch): 6 tasks created and 3 done ~1.5M + 3 merge-task writes ~0.3M + two index and actions ~1.4M ~ 4,200,000 ticks
(deftest ^{:seon.test/estimate {:ticks 4200000}} ready-merges-wait-for-running-merges-and-the-queue-closes-when-one-ends
  (main [["a" ["[:var x/f]"]] ["b" ["[:var x/g]"]] ["c" ["[:var x/k]"]]]
        (fn [db] (let [db (-> db (start-merge! "t/a-merge" :ready) (start-merge! "t/b-merge" :running))
                       acts (r/actions (r/index db))]
                   (is (not-any? #(= :merge (:op %)) acts) "b still runs: a's merge waits for it")
                   (is (not (contains? (spawned acts) "t/c-merge")) "a ended: the queue is closed to new merge tasks")
                   (let [db (d/db-with db [{:seon.task/id "t/b-merge" :seon.task/outcome :ready}])
                         merges (filter #(= :merge (:op %)) (r/actions (r/index db)))]
                     (is (= #{"t/a-merge" "t/b-merge"} (set (map :task merges))) "both merge, each alone through the writer's one :merge"))))))
