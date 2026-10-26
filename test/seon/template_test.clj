(ns seon.template-test
  "A request becomes its task at once: one :whole task on its own branch, written released in
  one transaction, and the reconciler forks its agent only from a main that holds it (task
  seon.template/a-request-is-one-linked-transaction).
  Run: bin/test seon.template-test"
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db :as pdb] [seon.task :as task] [seon.reconcile :as r] [seon.template :as tpl] [seon.fixtures :as fx] [clojure.string :as str]))

(def req {:seon.task/ns 'demo :seon.task/name "workouts" :seon.task/plan "store my workouts"
          :seon.task/proofs #{'demo-test/workouts-test}})

(defn- main [] (d/db-with (ts/test-db) (task/boot-tx "boot-1" 4)))

(defn- spawns [db] (filter #(= :spawn (:op %)) (r/actions (r/index db))))

(deftest a-request-is-one-transaction-and-forks-after-it
  (let [db (main)
        tx (tpl/request-tx db (tpl/request req))
        after (d/db-with db tx)
        ts (task/tasks after)]
    (is (vector? tx) "one transaction: the task and its release")
    (is (= :running (:seon.task/execution (ts "demo/workouts"))) "released by it")
    (is (empty? (spawns db)) "before it commits, no agent forks")
    (is (= [{:task "demo/workouts" :branch (task/default-branch "demo/workouts")}]
           (map (fn [a] {:task (:task a) :branch (get-in a [:env :branch])}) (spawns after)))
        "after it, its agent forks, on its branch")
    (is (= :seon.task/refused (:seon.error/type (tpl/request-tx after (tpl/request (assoc req :seon.task/needs #{"demo/nothing"})))))
        "an error answers as it is, and nothing is written")))

(deftest the-fit-tasks-are-requests-a-user-would-write
  ;; root, 2026-10-25 (the clean path): the five fit tasks restated as a user's problem
  ;; descriptions, each a request with no proof given (the agent writes its own, red), short
  (doseq [d fx/fit-requests]
    (is (not (contains? d :seon.task/proofs)) "no proof given: the agent writes its own")
    (is (< (count (:seon.task/plan d)) 300) (str "a short description: " (:seon.task/name d)))
    (is (seq (task/create-tx (ts/test-db) (tpl/request d))) "each a valid task set")))

(deftest a-request-is-one-task-one-agent-works-whole
  ;; owner, 2026-10-26: "build a do everything in one go task", then "switch to one-go for all
  ;; tests until we figure out a better solution": a request is one task of kind :whole on its
  ;; own branch, with the whole instructions
  (let [d {:seon.task/ns 'demo :seon.task/name "w" :seon.task/plan "Make w true."}
        [w :as ws] (tpl/request d)]
    (is (= 1 (count ws)))
    (is (= [:whole :task-demo-w] [(:seon.task/kind w) (:seon.task/branch w)]))
    (is (= (:whole tpl/instructions) (:seon.task/instructions w)))
    (is (str/includes? (:whole tpl/instructions) "main's work merged in") "done on main's merged program")
    ;; agent loop's park findings: a green run ends before its closing prose, so "end your turn
    ;; with prose" left the next turn parked :waiting; a false premise was reported in prose
    (is (not (str/includes? (:whole tpl/instructions) "end your turn")) "no closing-prose step")
    (is (str/includes? (:whole tpl/instructions) "(my.task/fail! ") "a false premise is a failed task")
    (is (seq (task/create-tx (ts/test-db) ws)) "a valid task")))

(deftest each-steps-instructions-are-its-task-briefly-prose-first
  ;; root, 2026-10-25: each step's instructions short, prose first (its goal, when it is done, by
  ;; what means: the standard format's stage task data), with no ;; anywhere
  (doseq [[k v] tpl/instructions]
    (is (str/starts-with? v "Your task: ") (str k ": its goal first"))
    (is (not (str/includes? v ";;")) (str k ": no ;; reaches the model"))
    (is (< (count v) 1200) (str k ": short (" (count v) " chars)"))))


(deftest a-requests-stages-are-one-sessions-agent
  ;; design agent-loop section 1a (owner, 2026-10-25: "just have it be the same session right?"):
  ;; a stage's run continues the agent of its request's earlier stages, found through :needs on
  ;; the one branch and the runs' own agents; a task on another branch is a session of its own
  (let [db (d/db-with (main) (tpl/request-tx (main) (fx/stage-decls req)))
        db (d/db-with db (task/run-start-tx "demo/workouts#1" "demo/workouts" "boot-1" :a1 {:branch (task/default-branch "demo/workouts") :model nil :max-turns 4} 1 "p"))
        ;; on a branch of its own, and needing design, whose run has an agent: still not design's session
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "other" :seon.task/plan "x" :seon.task/proofs #{'demo-test/other}
                                              :seon.task/needs #{"demo/workouts"}}]))]
    (is (= "boot-1/a1" (task/session-key db "demo/workouts-implement")) "implement continues design's agent")
    (is (= "boot-1/a1" (task/session-key db "demo/workouts-merge")) "merge too, through implement")
    (is (nil? (task/session-key db "demo/other")) "a task on another branch is not the session of a task it needs")
    (let [db (d/db-with db (task/run-start-tx "demo/workouts-implement#1" "demo/workouts-implement" "boot-2" :a4 {:branch (task/default-branch "demo/workouts") :model nil :max-turns 4} 2 "p"
                                              (task/session-key db "demo/workouts-implement")))]
      (is (= "boot-1/a1" (:seon.agent/key (:seon.run/agent (d/entity db [:seon.run/id "demo/workouts-implement#1"]))))
          "a process of a later boot, on the session's agent"))))
