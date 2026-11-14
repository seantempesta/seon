(ns seon.template-test
  "A request becomes its task set at once, written released in one transaction (by default one
  whole task a single agent works through and merges; the stages design -> implement -> merge on
  one branch are the experiments' choice), and the reconciler forks its first agent only from a
  main that holds all of it.
  Run: bin/test seon.template-test"
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is]] [clojure.edn] [my.test :as t] [my.root] [seon.program :as program] [seon.agent]
            [datahike.api :as d]
            [seon.db :as pdb] [seon.task :as task] [seon.reconcile :as r] [seon.template :as tpl] [seon.fixtures :as fx] [clojure.string :as str] [seon.config] [seon.contract]))

(defn- working-a-task
  "The help page's section on how a task is worked, as the checkout holds it: what :work and :whole instructions cite whole."
  []
  (let [t (slurp (str (seon.config/checkout) "/docs/agent.md"))
        a (str/index-of t "## Working a task")]
    (subs t a (str/index-of t "\n## " (inc a)))))

(def req {:seon.task/ns 'demo :seon.task/name "workouts" :seon.task/plan "store my workouts"
          :seon.task/proofs #{'demo-test/workouts-test}})

(defn- main [] (d/db-with (ts/test-db) (task/boot-tx "boot-1" "sean")))

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
  ;; (eight: the-related-list-says-what-each-lead-left went with the related part, task
  ;; seon.ctx/the-page-is-the-render-of-what-ctx-pulls, and a-mixed-collection-shows-no-empty-members is done)
  (is (= 8 (count fx/requests)))
  (is (apply distinct? (map :seon.task/name fx/requests)) "different requests")
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
        [w m :as ds] (tpl/design-build d)
        [w m] (map #(assoc % :seon.task/id (str (:seon.task/ns %) "/" (:seon.task/name %))) [w m])]
    (is (= [["w" :work] ["w-merge" :merge]] (mapv (juxt :seon.task/name :seon.task/kind) ds)) "exactly two tasks")
    (is (= #{"demo/w"} (:seon.task/needs m)) "the merge needs the work")
    (is (= [:task-demo-w :task-demo-w] (mapv :seon.task/branch ds)) "on one branch")
    (is (= [(:work tpl/instructions) (:merge tpl/instructions)] (mapv :seon.task/instructions ds)))
    ;; merging is read from data, never the kind (seon.task/merges?: no task on its own branch needs it)
    (is (not (task/merges? w [m])) "the work task does not merge itself: its merge task, on its branch, needs it")
    (is (task/merges? m []) "its merge task merges, nothing needing it")
    (is (task/merges? w []) "and a lone work task, which nothing needs, merges itself (rung 1's plain task, green and never merged)")
    (is (task/merges? w [(assoc m :seon.task/branch :task-demo-other)]) "a task that needs it from another branch leaves it its own merge")
    (is (not (str/includes? (:work tpl/instructions) "merge-main!")) "nor is it told to")
    ;; a work agent merges main every turn main moved (2026-10-30), so a conflict meets it: swarm's scripted work agent
    ;; never resolved one (4 of 10 swarm runs red at 20 tasks); the old sentence left it to the merge task
    (is (not (str/includes? (:work tpl/instructions) "docs/agent.md#merging")) "merging is read when the status has unmerged entries (it says where), not cited by every task")
    (is (str/includes? (:merge tpl/instructions) "docs/agent.md#merging") "a merge task cites it")
    ;; how a task is worked is the help page's section the instructions cite whole (a wiki section, its context's part)
    (doseq [k [:whole :work :implement]] (is (not (str/includes? (k tpl/instructions) "docs/agent.md#working-a-task")) (str k ": the system prompt says how a task is worked, not every task's context")))
    (let [w (working-a-task)
                  ;; (2026-10-29: the premise step applies to a fix only, and a proof written already is the
                  ;; task's: the agent writes only those its status lists with no source)
                  steps ["their failures are the reproduction" "with its source is written already" "with no source as a deftest beside the"
                         "(tests-reaching 'ns/f)" "never the live db" "egister it" "run it red" "(prove)" "make it pass"]]
      (is (every? #(str/includes? w %) steps) "each step is in the section")
      (is (= steps (sort-by #(or (str/index-of w %) -1) steps)) "in that order: the reproduction, the deftest, red, green"))
    (is (= [:design :implement :merge] (mapv :seon.task/kind (tpl/stages d))) "the staged choice still makes its tasks")
    (is (seq (task/create-tx (ts/test-db) ds)) "a valid task set")))

;; docs/merging.md#a-branch-lands-with-binland: a merge step is read from a task's needs and its branch, beside merges?
(deftest a-merge-step-needs-a-task-on-its-own-branch
  (let [w {:seon.task/id "demo/w"}
        m {:seon.task/id "demo/w-merge" :seon.task/branch :task-demo-w}
        on-w (assoc w :seon.task/branch :task-demo-w)]
    (is (task/merge-step? m [on-w] []) "it needs a task on its own branch, and nothing there needs it")
    (is (task/merge-step? (assoc m :seon.task/kind :work) [w] []) "a task with no branch of its own needing one on its default branch: its kind says nothing")
    (is (not (task/merge-step? (assoc m :seon.task/kind :merge) [] [])) "needing nothing: not, whatever its kind")
    (is (not (task/merge-step? m [(assoc w :seon.task/branch :task-demo-other)] [])) "a need on another branch is not its work")
    (is (not (task/merge-step? w [] [])) "a lone task")
    (is (and (not (task/merges? w [m])) (not (task/merge-step? w [] [m]))) "the work task a step follows is neither: it leaves its merge to the step and merges nothing of another's")
    (let [[design implement merge] (map #(assoc % :seon.task/id (task/task-id (:seon.task/ns %) (:seon.task/name %))) (tpl/stages {:seon.task/ns 'demo :seon.task/name "st" :seon.task/plan "Make st."}))]
      (is (not (task/merge-step? implement [design] [merge])) "a staged request's implement, between its design and its merge on one branch, is no merge step")
      (is (task/merge-step? merge [implement] []) "the task after it, the last on the branch, is"))
    (is (and (task/merges? m []) (task/merge-step? m [on-w] [])) "and a step with nothing on its branch needing it is both: it merges its work, and another's")
    (let [db (ts/test-db)
          decls (tpl/with-merge [{:seon.task/ns 'demo :seon.task/name "w" :seon.task/plan "Make w true."}])
          db (d/db-with db (task/create-tx db decls))]
      (is (false? (task/merge-step-on? db "demo/w")))
      (is (true? (task/merge-step-on? db "demo/w-merge"))))))

(deftest red-proofs-with-their-source-are-the-reproduction
  (let [w (working-a-task)
        k "docs/agent.md#working-a-task"]
    (is (str/includes? w "When your status shows red proofs with their source, their failures are the reproduction") (str k ": red proofs are the reproduction"))
    (is (str/includes? w "evaluate it whole; a long one, replace the part that changes, ``(seon.repl/edit! ") (str k ": edit! by the definition's size"))
    (is (str/includes? w "Check each change with the proof's own expression") (str k ": each change checked by the proof's expression"))))

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
    (is (str/includes? (:whole tpl/instructions) "Your status says") "it reads whether it can merge on its page")
    (is (str/includes? (:whole tpl/instructions) "your run then ends and root merges your branch"))
    ;; agent loop's park findings: a green run ends before its closing text, so "end your turn
    ;; with text" left the next turn parked :waiting; a false premise was reported in text
    (doseq [k [:whole :work]]
      (is (not (str/includes? (k tpl/instructions) "end your turn")) (str k ": no closing-text step"))
      ;; a false premise is a failed task: the system prompt says so (my.task/fail!), and the wiki section has the steps
      (is (not (str/includes? (k tpl/instructions) "docs/agent.md#working-a-task")) (str k ": does not cite the steps into every context")))
    (is (seq (task/create-tx (ts/test-db) ws)) "a valid task")))


(deftest a-requests-stages-are-one-sessions-agent
  ;; design agent-loop section 1a (owner, 2026-10-25: "just have it be the same session right?"):
  ;; a stage's agent continues the agent of its request's earlier stages, found through :needs on
  ;; the one branch and the tasks' own agents; a task on another branch is a session of its own
  (let [db (d/db-with (main) (tpl/request-tx (main) (tpl/stages req)))
        db (d/db-with db (task/start-tx db "demo/workouts" "boot-1" :a1 {:branch (task/default-branch "demo/workouts") :model nil :max-turns 4} 1))
        ;; on a branch of its own, and needing design, whose task has an agent: still not design's session
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "other" :seon.task/plan "x" :seon.task/proofs #{'demo-test/other}
                                              :seon.task/needs #{"demo/workouts"}}]))]
    (is (= "a1" (task/agent-key db "demo/workouts-implement")) "implement continues design's agent")
    (is (= "a1" (task/agent-key db "demo/workouts-merge")) "merge too, through implement")
    (is (nil? (task/agent-key db "demo/other")) "a task on another branch is not the session of a task it needs")
    (let [db (d/db-with db (task/start-tx db "demo/workouts-implement" "boot-2" (keyword (task/agent-key db "demo/workouts-implement")) {:branch (task/default-branch "demo/workouts") :model nil :max-turns 4} 2))]
      (is (= "a1" (:seon.agent/key (:seon.task/agent (d/entity db [:seon.task/id "demo/workouts-implement"]))))
          "a process of a later boot, on the session's agent"))))

;; docs/merging.md#a-slow-test-runs-last: its metric known ahead of time, itself its proof, filed once
(deftest a-slow-test-becomes-a-task-that-names-what-it-measured
  (let [db (t/world)
        tx (my.root/slow-tests-tx db '{app.x-test/waits 9000} 5000)
        tk (first (filter :seon.task/id tx))]
    (is (= "app.x-test/fast-waits" (:seon.task/id tk)))
    (is (= '#{app.x-test/waits} (set (:seon.task/proofs tk))) "itself its proof")
    (is (re-find #"`app.x-test/waits` measured 9000 ms, at or over the bound of 5000" (:seon.task/plan tk)) "its plan names its measure and the bound")
    (is (= :held (:seon.task/execution tk)) "held until released")
    (is (= [] (filterv :seon.task/id (my.root/slow-tests-tx (datahike.core/db-with db tx) '{app.x-test/waits 9000} 5000))) "filed once")))

;; docs/health.md#an-unfocused-docstring: one task a var, naming its measure and how it is done
(deftest ^{:seon.test/estimate {:ticks 300000}} an-unfocused-docstring-becomes-a-task-that-says-how-it-is-done
  (let [d (tpl/focus-docstring 'seon.task/entity-value 74 [:words :measurement])]
    (is (seon.contract/valid? :seon.task/declaration d))
    (is (= ['seon.task "focus-entity-value"] [(:seon.task/ns d) (:seon.task/name d)]) "one task a var, in its namespace")
    (is (re-find #"is 74 words \(words, measurement\)" (:seon.task/plan d)) "its plan names its measure")
    (is (re-find #"Change no code\. Done when \(seon\.problems/unfocused-docstrings \(my\.db/db\) \{\} #\{'seon\.task/entity-value\}\) answers \[\]" (:seon.task/plan d))
        "and the one eval that says it is done")))
