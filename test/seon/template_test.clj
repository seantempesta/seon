(ns seon.template-test
  "A request becomes its linked task set at once (owner, 2026-10-22): design -> implement ->
  merge on one branch, written released in one transaction, and the reconciler forks its
  first agent only from a main that holds all of it (task
  seon.template/a-request-is-one-linked-transaction).
  Run: bin/test seon.template-test"
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db :as pdb] [seon.task :as task] [seon.reconcile :as r] [seon.template :as tpl] [seon.fixtures :as fx] [clojure.string :as str]))

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

(deftest the-fit-tasks-are-requests-a-user-would-write
  ;; root, 2026-10-25 (the clean path): the five fit tasks restated as a user's problem
  ;; descriptions, each a request: design, implement and merge on one branch, no proof given
  ;; (design writes its own, red), short, and each step's instructions prose first, with no ;;
  (doseq [d fx/fit-requests :let [[design implement merge] (tpl/request d)]]
    (is (= [:design :implement :merge] (mapv :seon.task/kind [design implement merge])) (:seon.task/name d))
    (is (apply = (map :seon.task/branch [design implement merge])) "one branch")
    (is (not (contains? d :seon.task/proofs)) "no proof given: design writes its own")
    (is (< (count (:seon.task/plan d)) 300) (str "a short description: " (:seon.task/name d)))
    (is (seq (task/create-tx (ts/test-db) (tpl/request d))) "each a valid task set")))

(deftest each-steps-instructions-are-its-task-briefly-prose-first
  ;; root, 2026-10-25: each step's instructions short, prose first (its goal, when it is done, by
  ;; what means: the standard format's stage stories), with no ;; anywhere
  (doseq [[k v] tpl/instructions]
    (is (str/starts-with? v "Your task: ") (str k ": its goal first"))
    (is (not (str/includes? v ";;")) (str k ": no ;; reaches the model"))
    (is (< (count v) 1200) (str k ": short (" (count v) " chars)")))
  ;; live agents round 3 (2026-10-25, notes/live-2026-10-25.md A2): a design agent that was told
  ;; to write deftests but not how found the checkout on disk, appended its deftest to the test file
  ;; and load-file'd it; a deftest is evaluated in its namespace, recorded on the branch
  (is (str/includes? (:design tpl/instructions) "the call's ns set to its test namespace") "design says where a proof is evaluated, the REPL kept in its own")
  ;; the thinking-off designs of 2026-10-26 registered a red proof at turns 7, 12 and 21, then
  ;; tried the fix until the cap and never wrote the plan
  (is (str/includes? (:design tpl/instructions) "Once the proof is red, write the plan") "the plan follows the red proof")
  (is (str/includes? (:design tpl/instructions) "Do not try the fix") "the fix is the implement step's")
  (is (str/includes? (:design tpl/instructions) "you write no file"))
  ;; pipeline 1 (notes/live-2026-10-25.md F4): both printer designs reproduced their bug by turn 11,
  ;; then read on to the cap and registered no proof; reading came before the proof, with no end.
  ;; The design reproduces the bug as a deftest, registers it and runs it red, then reads
  (let [d (:design tpl/instructions)
        steps ["reproduce what the plan says is wrong" "as a deftest" "register it" "run it red" "Then read what the change touches"]]
    (is (every? #(str/includes? d %) steps) "each step is in the design instructions")
    (is (= steps (sort-by #(or (str/index-of d %) -1) steps)) "proof first: reproduce as a deftest, register, run red, then read")))


(deftest a-requests-stages-are-one-sessions-agent
  ;; design agent-loop section 1a (owner, 2026-10-25: "just have it be the same session right?"):
  ;; a stage's run continues the agent of its request's earlier stages, found through :needs on
  ;; the one branch and the runs' own agents; a task on another branch is a session of its own
  (let [db (d/db-with (main) (tpl/request-tx (main) req))
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
