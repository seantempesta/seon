(ns seon.template-test
  "A request becomes its task set at once, written released in one transaction (by default one
  whole task a single agent works through and merges; the stages design -> implement -> merge on
  one branch are the experiments' choice), and the reconciler forks its first agent only from a
  main that holds all of it (task seon.template/a-request-is-one-linked-transaction).
  Run: bin/test seon.template-test"
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is]] [clojure.edn] [my.test :as t] [my.root] [seon.program :as program] [seon.agent]
            [datahike.api :as d]
            [seon.db :as pdb] [seon.task :as task] [seon.reconcile :as r] [seon.template :as tpl] [seon.fixtures :as fx] [clojure.string :as str]))

(def req {:seon.task/ns 'demo :seon.task/name "workouts" :seon.task/plan "store my workouts"
          :seon.task/proofs #{'demo-test/workouts-test}})

(defn- main [] (d/db-with (ts/test-db) (task/boot-tx "boot-1" 4)))

(defn- spawns [db] (filter #(= :spawn (:op %)) (r/actions (r/index db))))

(deftest the-stages-are-design-implement-merge-on-one-branch
  (let [[design implement merge :as ds] (tpl/stages req)
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
        tx (tpl/request-tx db (tpl/stages req))
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
    (is (= :seon.task/refused (:seon.error/type (tpl/request-tx after (tpl/stages (assoc req :seon.task/needs #{"demo/nothing"})))))
        "an error answers as it is, and nothing is written")))

(deftest the-measured-requests-are-a-users-with-their-reproduction
  ;; the capped runs of 2026-10-26: four of ten requests were already done on main and nothing on
  ;; the page said so; each request now carries the one form that reproduces it, run on main first
  (is (= 10 (count fx/requests)))
  (is (apply distinct? (map :seon.task/name fx/requests)) "ten different requests")
  (doseq [d fx/requests]
    (is (not (contains? d :seon.task/proofs)) "no proof given: the agent writes its own")
    (is (re-find #"Reproduce: [\(\[].*[\)\]] answers [\[\"0-9a-z].*; it should" (:seon.task/plan d)) (str "a reproduction, what it answers and what it should: " (:seon.task/name d)))
    (is (< (count (:seon.task/plan d)) 1000) (str "short: " (:seon.task/name d)))
    ;; a cause carried from a task entry is that entry's own words, so the two cannot drift
    (when-let [[_ tid cause] (re-find #"Its cause \(task ([\w./-]+)\): (.*)$" (:seon.task/plan d))]
      (let [[tns tname] (str/split tid #"/" 2)
            entry (some #(when (= tname (:seon.task/name %)) %) (clojure.edn/read-string (slurp (str (seon.config/checkout) "/resources/seon/tasks/" tns ".edn"))))
            words #(str/replace % #"`" "")]
        (is (some? entry) (str "its task entry exists: " tid))
        (is (str/includes? (words (:seon.task/plan entry)) (str/replace (words cause) #"\.$" ""))
            (str "the cause is the entry's own words: " tid))))
    (is (seq (task/create-tx (ts/test-db) (tpl/request d))) "each a valid task set")))

(deftest a-design-build-request-is-its-design-build-task-and-its-merge
  ;; owner, 2026-10-26: "make the default tests into two tasks -- design-build and merge", then the
  ;; one task again as the default: an experiment's choice, the design-build task (kind :work, the
  ;; :work instructions: red proof, then green, no merge of its own) and its merge task, needing it
  (let [d {:seon.task/ns 'demo :seon.task/name "w" :seon.task/plan "Make w true."}
        [w m :as ds] (tpl/design-build d)]
    (is (= [["w" :work] ["w-merge" :merge]] (mapv (juxt :seon.task/name :seon.task/kind) ds)) "exactly two tasks")
    (is (= #{"demo/w"} (:seon.task/needs m)) "the merge needs the work")
    (is (= [:task-demo-w :task-demo-w] (mapv :seon.task/branch ds)) "on one branch")
    (is (= [(:work tpl/instructions) (:merge tpl/instructions)] (mapv :seon.task/instructions ds)))
    (is (not (task/merges? (:seon.task/kind w))) "the work task does not merge itself")
    (is (not (str/includes? (:work tpl/instructions) "merge-main!")) "nor is it told to")
    ;; the capped runs of 2026-10-26 (notes: context-for-20-turns): three agents hunted a bug main had
    ;; already fixed, five had what they needed by turns 1-18 and read on, and proofs were built on the
    ;; live db and on zipmap's order: reproduce first and fail! if it is right, stop reading once it is
    ;; wrong and write the deftest red, and build its input as the existing tests build theirs
    (doseq [k [:whole :work]
            :let [w (k tpl/instructions)
                  ;; (2026-10-29: the premise step applies to a fix only, and a proof written already is the
                  ;; task's: the agent writes only those its status lists with no source)
                  steps ["first reproduce it in one eval" "if the output is already right" "(my.task/fail! "
                         "once the output is wrong, stop reading" "with its source is written already" "with no source as a deftest beside the"
                         "(my.agent/tests-reaching 'ns/f)" "never the live db" "register it" "run it red" "(my.agent/prove)" "make it pass"]]]
      (is (every? #(str/includes? w %) steps) (str k ": each step is in the instructions"))
      (is (= steps (sort-by #(or (str/index-of w %) -1) steps)) (str k ": in that order: reproduce, fail! or stop reading, the deftest, red, green")))
    (is (= [:design :implement :merge] (mapv :seon.task/kind (tpl/stages d))) "the staged choice still makes its tasks")
    (is (seq (task/create-tx (ts/test-db) ds)) "a valid task set")))

(deftest a-request-is-one-whole-task-one-agent-works-through
  ;; owner, 2026-10-26: "build a do everything in one go task", then the default: a request is one
  ;; task of kind :whole on its own branch, with the whole instructions
  (let [d {:seon.task/ns 'demo :seon.task/name "w" :seon.task/plan "Make w true."}
        [w :as ws] (tpl/request d)]
    (is (= 1 (count ws)))
    (is (= [:whole :task-demo-w] [(:seon.task/kind w) (:seon.task/branch w)]))
    (is (= (:whole tpl/instructions) (:seon.task/instructions w)))
    (is (str/includes? (:whole tpl/instructions) "main's work merged in") "done on main's merged program")
    ;; owner, 2026-10-26: the agent reads its merge readiness on its page and is done when it can merge
    (is (str/includes? (:whole tpl/instructions) "status's :merge") "it reads whether it can merge on its page")
    (is (str/includes? (:whole tpl/instructions) "Once :merge says :ready, your run ends and root merges your branch"))
    ;; agent loop's park findings: a green run ends before its closing prose, so "end your turn
    ;; with prose" left the next turn parked :waiting; a false premise was reported in prose
    (doseq [k [:whole :work]]
      (is (not (str/includes? (k tpl/instructions) "end your turn")) (str k ": no closing-prose step"))
      (is (str/includes? (k tpl/instructions) "(my.task/fail! ") (str k ": a false premise is a failed task")))
    (is (seq (task/create-tx (ts/test-db) ws)) "a valid task")))


(deftest a-requests-stages-are-one-sessions-agent
  ;; design agent-loop section 1a (owner, 2026-10-25: "just have it be the same session right?"):
  ;; a stage's run continues the agent of its request's earlier stages, found through :needs on
  ;; the one branch and the runs' own agents; a task on another branch is a session of its own
  (let [db (d/db-with (main) (tpl/request-tx (main) (tpl/stages req)))
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

(deftest a-long-form-becomes-a-task-that-names-its-size-tests-and-ticks
  ;; task seon.template/a-finding-is-a-task (docs/health.md#a-form-too-long): a function over the bound is a
  ;; held task from its template, its proofs the tests reaching it, its plan naming its size and how to
  ;; measure its cost; one under the bound is none, and a var whose task main holds is filed once
  (let [bound (seon.config/value :seon.config.shrink/form-tokens)
        db (t/with (t/world)
                   (t/code 'app.long (str "(ns app.long)\n(defn big [] \"" (apply str (repeat (* 4 bound) \x)) "\")\n(defn small [] 1)\n"))
                   (t/code 'app.long-test "(ns app.long-test (:require [clojure.test :refer [deftest is]] [app.long :as l]))\n(deftest big-is-text (is (string? (l/big))))\n"))
        g (program/reach-indexed (program/reach-graph db nil) (second (seon.agent/test-inputs db)))
        tx (my.root/findings-tx db g)
        tk (first (filter #(= "app.long/shrink-big" (:seon.task/id %)) tx))]
    (is (some? tk) "the long function's task")
    (is (= #{'app.long-test/big-is-text} (set (:seon.task/proofs tk))) "its proofs: the tests reaching it")
    (is (re-find #"`app.long/big` is \d+ tokens, over the bound of" (:seon.task/plan tk)) "its plan names its size and the bound")
    (is (re-find #"my.agent/cost 'app.long/big" (:seon.task/plan tk)) "and how to measure its cost before it starts")
    (is (= :held (:seon.task/execution tk)) "held until released")
    (is (not-any? #(re-find #"shrink-small" (str (:seon.task/id %))) tx) "a function under the bound is no task")
    (is (= [] (filterv :seon.task/id (my.root/findings-tx (datahike.core/db-with db tx) g))) "filed once")))
