(ns seon.template-test
  "A request becomes its linked task set at once (owner, 2026-10-22): design -> implement ->
  merge on one branch, written released in one transaction, and the reconciler forks its
  first agent only from a main that holds all of it (task
  seon.template/a-request-is-one-linked-transaction).
  Run: bin/test seon.template-test"
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db :as pdb] [seon.task :as task] [seon.reconcile :as r] [seon.template :as tpl]))

(def req {:seon.task/ns 'demo :seon.task/name "workouts" :seon.task/plan "store my workouts"
          :seon.task/proofs #{'demo-test/workouts-test}})

(defn- main [] (d/db-with (ts/test-db) (task/boot-tx "boot-1" 4)))

(defn- spawns [db] (filter #(= :spawn (:op %)) (r/actions (r/index db))))

(deftest a-request-is-design-implement-merge-on-one-branch
  (let [[design implement merge :as ds] (tpl/request req)
        branch (task/default-branch "demo/workouts")]
    (is (= [:design :implement :merge] (map :seon.task/kind ds)))
    (is (= ["workouts" "workouts-implement" "workouts-merge"] (map :seon.task/name ds)))
    (is (= [nil #{"demo/workouts"} #{"demo/workouts-implement"}] (map :seon.task/needs ds)) "linked by :needs")
    (is (= #{branch} (set (map :seon.task/branch ds))) "implement and merge check out design's branch, so each holds the last step's work")
    (is (= "store my workouts" (:seon.task/plan design)) "the request's words are design's plan")
    (is (= (tpl/instructions :implement) (:seon.task/instructions implement)))
    (is (= (tpl/instructions :merge) (:seon.task/instructions merge)))))

(deftest a-request-is-one-transaction-and-forks-after-it
  (let [db (main)
        tx (tpl/request-tx db req)
        after (d/db-with db tx)
        ts (task/tasks after)
        ids ["demo/workouts" "demo/workouts-implement" "demo/workouts-merge"]]
    (is (vector? tx) "one transaction: the set and its release")
    (is (= #{:running} (set (map #(:seon.task/execution (ts %)) ids))) "every task of the set is released by it")
    (is (= #{"demo/workouts"} (:seon.task/needs (ts "demo/workouts-implement"))))
    (is (= #{"demo/workouts-implement"} (:seon.task/needs (ts "demo/workouts-merge"))))
    (is (empty? (spawns db)) "before it commits, no agent of the set forks")
    (is (= [{:task "demo/workouts" :branch (task/default-branch "demo/workouts")}]
           (map (fn [a] {:task (:task a) :branch (get-in a [:env :branch])}) (spawns after)))
        "after it, only design forks, on the set's branch")
    (is (= #{'demo-test/workouts-test} (:proofs (:seon.task/accumulated (ts "demo/workouts-implement"))))
        "the request's proofs reach implement along :needs")
    (is (= :seon.task/refused (:seon.error/type (tpl/request-tx after (assoc req :seon.task/needs #{"demo/nothing"}))))
        "an error answers as it is, and nothing is written")))
