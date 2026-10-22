(ns r3.octopus-test
  "Octopus-merge rules of seon.reconcile/actions, over a small main: disjoint merges run and merge
  together; overlapping ones serialize; unknown changes run alone; an octopus merge closes when its
  first member ends; it merges only once no merge attempt is running."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [seon.reconcile :as r] [seon.task :as task]
            [seon.template :as tpl] [seon.db :as pdb] [datahike.api :as d]))

(def boot "b")

(defn- main
  "(f conn) on a branch of the test store holding work tasks (names) each done with the
  changed keys given, their merges released; the branch is deleted when f returns."
  [changes f]
  (ts/test-branch! (fn [_ conn] (let [ds (tpl/with-merge (vec (for [[nm _] changes] {:seon.task/ns 't :seon.task/name nm :seon.task/plan "p"
                                                         :seon.task/proofs #{(symbol "t" (str nm "-test"))}})))]
    (d/transact conn {:tx-data (into (task/boot-tx boot 16) [{:seon.root/id :root :seon.config.merge/max-running 8}])})
    (d/transact conn {:tx-data (task/create-tx @conn ds)})
    (d/transact conn {:tx-data (task/execution-tx (mapv #(task/task-id 't (:seon.task/name %)) ds) :running)})
    (doseq [[nm ks] changes :let [id (str "t/" nm)]]
      (d/transact conn {:tx-data (into (task/attempt-start-tx (str id "#1") id boot :a {:branch (task/default-branch id) :model nil :max-turns 8} 0 "p")
                                       [(cond-> {:seon.attempt/id (str id "#1") :seon.attempt/status :done}
                                          ks (assoc :seon.attempt/changed (vec ks)))])}))
    (f conn)))))

(defn- spawned [acts] (set (for [a acts :when (= :spawn (:op a))] (:task a))))

(defn- start-merge! [conn id status]
  (d/transact conn {:tx-data (into (task/attempt-start-tx (str id "#1") id boot :m {:branch (task/default-branch (subs id 0 (- (count id) 6))) :model nil :max-turns 8} 0 "p")
                                   [{:seon.attempt/id (str id "#1") :seon.attempt/status status
                                     :seon.attempt/results [{:seon.proof/test (symbol "t" (str (subs id 2 (- (count id) 6)) "-test")) :seon.proof/status :pass}]}])}))

(deftest disjoint-merges-run-together-overlapping-serialize
  (main [["a" ["[:var x/f]"]] ["b" ["[:var x/g]"]] ["c" ["[:var x/f]" "[:var x/h]"]]] (fn [conn] (is (= #{"t/a-merge" "t/b-merge"} (spawned (r/reconcile @conn))) "a and b are disjoint; c overlaps a and waits"))))

(deftest unknown-changes-run-alone
  (main [["a" nil] ["b" ["[:var x/g]"]]] (fn [conn] (is (= 1 (count (spawned (r/reconcile @conn)))) "a merge whose changes no attempt measured runs alone"))))

(deftest an-octopus-merge-waits-for-running-merges-and-closes-when-a-member-ends
  (main [["a" ["[:var x/f]"]] ["b" ["[:var x/g]"]] ["c" ["[:var x/k]"]]] (fn [conn] (start-merge! conn "t/a-merge" :ended)
    (start-merge! conn "t/b-merge" :running)
    (let [acts (r/reconcile @conn)]
      (is (not-any? #(= :octopus-merge (:op %)) acts) "b still runs: the octopus merge waits for it")
      (is (not (contains? (spawned acts) "t/c-merge")) "a ended: the octopus merge is closed to new members"))
    (d/transact conn {:tx-data [{:seon.attempt/id "t/b-merge#1" :seon.attempt/status :ended}]})
    (let [octopus (first (filter #(= :octopus-merge (:op %)) (r/reconcile @conn)))]
      (is (= ["t/a-merge#1" "t/b-merge#1"] (mapv :attempt (:members octopus))) "both merge in one octopus merge")))))
