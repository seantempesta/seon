(ns seon.task-test
  "The task tree (runtime-follows-db §2): child tasks and needs as facts, the writer's
  needs-cycle refusal, the attempt and firing facts adopted on a branch of the live store."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is are]]
            [seon.db :as db]
            [seon.id :as id]
            [seon.task :as task]
            [seon.test-support :as test-support]))

(def ^:private task-path "resources/seon/tasks/tq.tree.edn")

(defn- entry
  "One task EDN entry of tq.tree named `task-name` with the authored `members`."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [task-name members]
  (str "#:seon.task{:name \"" task-name "\" :kind :work :intent \"Do " task-name ".\" " members "}"))

(defn- needs
  "The `:seon.task/needs` member naming the tq.tree tasks `names`."
  {:malli/schema [:=> [:cat [:* :string]] :string]}
  [& names]
  (str ":needs #{" (str/join " " (map #(str "[:seon.task/sym tq.tree/" % "]") names)) "}"))

(defn- ingest!
  "Index the tq.tree task file made of `entries`."
  {:malli/schema [:=> [:cat :seon.db/connection [:* :string]] :seon.schema/value]}
  [connection & entries]
  (task/index-tasks! {:seon.db/connection connection :seon.task/files {task-path (str "[" (str/join " " entries) "]")}}))

(defn- needed
  "The task symbols tq.tree/`task-name` needs at `connection`'s head."
  {:malli/schema [:=> [:cat :seon.db/connection :string] [:set :seon.task/sym]]}
  [connection task-name]
  (set (db/q '[:find [?s ...] :in $ ?t :where [?e :seon.task/sym ?t] [?e :seon.task/needs ?n] [?n :seon.task/sym ?s]]
             (db/db connection) (symbol "tq.tree" task-name))))

(deftest ^{:seon/tasks #{'seon.task/a-needs-cycle-is-refused-at-the-writer}} a-needs-cycle-is-refused-at-the-writer
  (test-support/with-database
   (fn [connection]
     (is (true? (:seon.task/changed? (ingest! connection (entry "a" "") (entry "b" (needs "a"))))))
     (is (= #{'tq.tree/a} (needed connection "b")) "a need authored as a lookup ref is a ref to the task")
     (are [entries] (= :needs-cycle (:seon.task/refused (apply ingest! connection entries)))
       [(entry "a" (needs "b")) (entry "b" (needs "a"))]
       [(entry "a" (needs "a")) (entry "b" (needs "a"))]
       [(entry "a" ":children #{[:seon.task/sym tq.tree/b]}") (entry "b" (needs "a"))]
       [(entry "a" ":children #{[:seon.task/sym tq.tree/c]}") (entry "b" (needs "a")) (entry "c" (needs "b"))]
       [(entry "p" (str ":children #{[:seon.task/sym tq.tree/c]} " (needs "x"))) (entry "c" "") (entry "x" (needs "c"))])
     (is (= #{'tq.tree/a} (needed connection "b")) "a refused write leaves the tree as it was")
     (is (true? (:seon.task/changed? (ingest! connection (entry "p" ":children #{[:seon.task/sym tq.tree/a] [:seon.task/sym tq.tree/b]}")
                                              (entry "a" "") (entry "b" (needs "a")) (entry "c" (needs "p")))))
         "a sibling that needs another and a task that needs the parent are no cycle"))))

(deftest ^{:seon/tasks #{'seon.task/a-needs-cycle-is-refused-at-the-writer}} the-task-facts-adopt-on-a-branch-of-the-live-store
  (test-support/with-database
   (fn [connection]
     (ingest! connection (entry "p" ":children #{[:seon.task/sym tq.tree/a]}") (entry "a" "") (entry "b" (needs "p")))
     (test-support/transacted! connection (test-support/agent-tx (db/db connection) "tq-tree-agent"))
     (let [handle (task/task-handle 'tq.tree "a" :work)
           assignment (id/id [handle "tq-tree-agent" :tq-tree])
           firing (id/id [handle #inst "2026-10-11T00:00:00Z"])
           commit (random-uuid)]
       (test-support/transacted!
        connection
        [{:db/id [:seon.task/id handle] :seon.task/execution :held :seon.task/instructions "Run these."
          :seon.task/cron "0 3 * * *" :seon.task/zone "UTC" :seon.task/function [:seon.fn/sym 'seon.task/create!]}
         {:seon.task/assignment assignment :seon.task/assigned handle :seon.task/attempt-of [:seon.task/id handle]
          :seon.task/revision (task/task-revision (db/db connection) handle) :seon.task/agent [:seon.agent/id "tq-tree-agent"]
          :seon.task/branch :tq-tree :seon.task.attempt/end :sent-back :seon.task.attempt/target [:seon.task/id handle]
          :seon.task.attempt/reason "The proof asserts the old shape." :seon.task.attempt/commit commit}
         {:seon.task.fire/id firing :seon.task.fire/at #inst "2026-10-11T00:00:00Z" :seon.task.fire/of [:seon.task/id handle]
          :seon.task.fire/to [:seon.agent/id "tq-tree-agent"]}])
       (let [database (db/db connection)
             sym-of #(:seon.task/sym (db/pull database [:seon.task/sym] (:db/id %)))]
         (is (= {:seon.task/children #{'tq.tree/a}}
                (update-vals (db/pull database [:seon.task/children] [:seon.task/sym 'tq.tree/p]) #(set (map sym-of %)))))
         (is (= {:seon.task/needs #{'tq.tree/p}}
                (update-vals (db/pull database [:seon.task/needs] [:seon.task/sym 'tq.tree/b]) #(set (map sym-of %)))))
         (is (= {:seon.task/execution :held :seon.task/instructions "Run these." :seon.task/cron "0 3 * * *" :seon.task/zone "UTC"}
                (db/pull database [:seon.task/execution :seon.task/instructions :seon.task/cron :seon.task/zone] [:seon.task/id handle])))
         (is (= 'seon.task/create!
                (:seon.fn/sym (:seon.task/function (db/pull database [{:seon.task/function [:seon.fn/sym]}] [:seon.task/id handle])))))
         (is (= {:seon.task.attempt/end :sent-back :seon.task.attempt/reason "The proof asserts the old shape." :seon.task.attempt/commit commit
                 :seon.task/attempt-of 'tq.tree/a :seon.task.attempt/target 'tq.tree/a}
                (-> (db/pull database [:seon.task.attempt/end :seon.task.attempt/reason :seon.task.attempt/commit
                                       :seon.task/attempt-of :seon.task.attempt/target] [:seon.task/assignment assignment])
                    (update :seon.task/attempt-of sym-of) (update :seon.task.attempt/target sym-of))))
         (is (= {:seon.task.fire/at #inst "2026-10-11T00:00:00Z" :seon.task.fire/of 'tq.tree/a}
                (update (db/pull database [:seon.task.fire/at :seon.task.fire/of] [:seon.task.fire/id firing]) :seon.task.fire/of sym-of))))))))
