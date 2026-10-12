(ns seon.task-test
  "The task tree (runtime-follows-db §2): child tasks and needs as facts, the writer's
  needs-cycle refusal, the attempt and firing facts adopted on a branch of the live store."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is are]]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.id :as id]
            [seon.profile :as profile]
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

(deftest ^{:seon/tasks #{'seon.task/a-step-selects-its-obligations-by-its-kind}} obligations-select-by-step
  (test-support/with-database
   (fn [connection]
     (ingest! connection (entry "ordinary" "") (entry "implement" ":step :implement")
              (entry "prove" ":step :prove") (entry "prove-own" ":step :prove")
              (entry "body" (needs "prove")) (entry "body-own" (needs "prove-own")))
     (doseq [[name tag] [['ordinary 'ordinary] ['implementation 'implement]
                       ['product 'body] ['own 'prove-own] ['product-own 'body-own]]]
       (test-support/declare-proof! connection 'tq.obligations name (symbol "tq.tree" (str tag))))
     (test-support/transacted! connection (test-support/agent-tx (db/db connection) "tq-obligations-agent"))
     (doseq [[name kind proofs product]
             [["ordinary" :implement '#{tq.obligations/ordinary} #{}]
              ["implement" :implement '#{tq.obligations/implementation} #{}]
              ["prove" :prove #{} '#{tq.obligations/product}]
              ["prove-own" :prove '#{tq.obligations/own} '#{tq.obligations/product-own}]]]
       (is (= {:seon.task/step kind :seon.task/proofs proofs :seon.task/product product}
              (task/obligations (db/db connection) (symbol "tq.tree" name))))
       (is (= proofs (:seon.task/proofs
                      (task/assignment-read (db/db connection) (task/task-handle 'tq.tree name :work)
                                            [:seon.agent/id "tq-obligations-agent"])))))
     (is (= {:seon.task/step :implement :seon.task/proofs #{} :seon.task/product #{}}
            (task/obligations (db/db connection) 'tq.tree/missing))))))

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

(deftest ^{:seon/tasks #{'seon.task/a-needs-cycle-is-refused-at-the-writer 'seon.task/a-step-selects-its-obligations-by-its-kind}} the-task-facts-adopt-on-a-branch-of-the-live-store
  (test-support/with-database
   (fn [connection]
     (is (= :seon.error/unimplemented
            (:seon.error/type (refusal/error :seon.error/unimplemented
                                            {:seon.error/unimplemented-function 'tq.tree/stub :seon.error/operation 'tq.tree/stub :seon.error/message "Not implemented."}))))
     (ingest! connection (entry "p" ":children #{[:seon.task/sym tq.tree/a]}") (entry "a" "") (entry "b" (needs "p")))
     (test-support/transacted! connection (test-support/agent-tx (db/db connection) "tq-tree-agent"))
     (let [handle (task/task-handle 'tq.tree "a" :work)
           assignment (id/id [handle "tq-tree-agent" :tq-tree])
           firing (id/id [handle #inst "2026-10-11T00:00:00Z"])
           commit (random-uuid)]
       (test-support/transacted!
        connection
        [{:db/id [:seon.task/id handle] :seon.task/step :prove :seon.task/execution :held :seon.task/instructions "Run these."
          :seon.task/cron "0 3 * * *" :seon.task/zone "UTC" :seon.task/function [:seon.fn/sym 'seon.task/create!]}
         {:seon.task/assignment assignment :seon.task/assigned handle :seon.task/attempt-of [:seon.task/id handle]
          :seon.task/revision (task/task-revision (db/db connection) handle) :seon.task/agent [:seon.agent/id "tq-tree-agent"]
          :seon.task/branch :tq-tree :seon.task.attempt/end :error :seon.task.attempt/relaunch "The execution is repaired."
          :seon.task.attempt/relaunch-fork commit :seon.task.attempt/target [:seon.task/id handle]
          :seon.task.attempt/reason "The proof asserts the old shape." :seon.task.attempt/commit commit}
         {:seon.task.fire/id firing :seon.task.fire/at #inst "2026-10-11T00:00:00Z" :seon.task.fire/of [:seon.task/id handle]
          :seon.task.fire/to [:seon.agent/id "tq-tree-agent"]}])
       (let [database (db/db connection)
             sym-of #(:seon.task/sym (db/pull database [:seon.task/sym] (:db/id %)))]
         (is (= {:seon.task/children #{'tq.tree/a}}
                (update-vals (db/pull database [:seon.task/children] [:seon.task/sym 'tq.tree/p]) #(set (map sym-of %)))))
         (is (= {:seon.task/needs #{'tq.tree/p}}
                (update-vals (db/pull database [:seon.task/needs] [:seon.task/sym 'tq.tree/b]) #(set (map sym-of %)))))
         (is (= {:seon.task/step :prove :seon.task/execution :held :seon.task/instructions "Run these." :seon.task/cron "0 3 * * *" :seon.task/zone "UTC"}
                (db/pull database [:seon.task/step :seon.task/execution :seon.task/instructions :seon.task/cron :seon.task/zone] [:seon.task/id handle])))
         (is (= 'seon.task/create!
                (:seon.fn/sym (:seon.task/function (db/pull database [{:seon.task/function [:seon.fn/sym]}] [:seon.task/id handle])))))
         (is (= {:seon.task.attempt/end :error :seon.task.attempt/relaunch "The execution is repaired."
                 :seon.task.attempt/relaunch-fork commit :seon.task.attempt/reason "The proof asserts the old shape." :seon.task.attempt/commit commit
                 :seon.task/attempt-of 'tq.tree/a :seon.task.attempt/target 'tq.tree/a}
                (-> (db/pull database [:seon.task.attempt/end :seon.task.attempt/relaunch :seon.task.attempt/relaunch-fork
                                       :seon.task.attempt/reason :seon.task.attempt/commit
                                       :seon.task/attempt-of :seon.task.attempt/target] [:seon.task/assignment assignment])
                    (update :seon.task/attempt-of sym-of) (update :seon.task.attempt/target sym-of))))
         (is (= {:seon.task.fire/at #inst "2026-10-11T00:00:00Z" :seon.task.fire/of 'tq.tree/a}
                (update (db/pull database [:seon.task.fire/at :seon.task.fire/of] [:seon.task.fire/id firing]) :seon.task.fire/of sym-of))))))))

;;; READY: P1's tree of fifty leaves (design-probes.md): subject → design; prove needs design;
;;; N fan-out leaves need prove; b needs prove and holds schema, then M bodies needing schema;
;;; integrate needs every fan-out leaf and b; merge needs integrate and is held.

(def ^:private fan-out (mapv #(str "f" %) (range 1 24)))

(def ^:private bodies (mapv #(str "body" %) (range 1 23)))

(defn- children
  "The `:seon.task/children` member naming the tq.tree tasks `names`."
  {:malli/schema [:=> [:cat [:sequential :string]] :string]}
  [names]
  (str ":children #{" (str/join " " (map #(str "[:seon.task/sym tq.tree/" % "]") names)) "}"))

(defn- tree-entries
  "The fifty-leaf tree's task EDN entries."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  []
  (-> [(entry "subject" (children (concat ["design" "prove" "b" "integrate" "merge"] fan-out)))
       (entry "design" "") (entry "prove" (needs "design"))
       (entry "b" (str (children (cons "schema" bodies)) " " (needs "prove"))) (entry "schema" "")
       (entry "integrate" (apply needs "b" fan-out)) (entry "merge" (needs "integrate"))]
      (into (map #(entry % (needs "prove"))) fan-out)
      (into (map #(entry % (needs "schema"))) bodies)))

(defn- handle
  {:malli/schema [:=> [:cat :string] :seon.task/handle]}
  [task-name]
  (task/task-handle 'tq.tree task-name :work))

(defn- ended
  "An attempt row of tq.tree/`task-name` by the fixture agent, ended `end` (open when nil), sent
  back to `target` when given; `n` keeps a task's second attempt distinct."
  {:malli/schema [:=> [:cat :seon.db/database-value :string [:maybe :seon.task.attempt/end] [:maybe :string] :int] :map]}
  [database task-name end target n]
  (cond-> {:seon.task/assignment (id/id [(handle task-name) "tq-tree-agent" n]) :seon.task/assigned (handle task-name)
           :seon.task/attempt-of [:seon.task/id (handle task-name)] :seon.task/revision (task/task-revision database (handle task-name))
           :seon.task/agent [:seon.agent/id "tq-tree-agent"] :seon.task/branch (keyword (str "tq-" task-name "-" n))}
    end (assoc :seon.task.attempt/end end)
    target (assoc :seon.task.attempt/target [:seon.task/id (handle target)])))

(defn- after
  "`database` after each transaction of `txs` in turn, never committed; each step's `[name end]`
  pairs become attempt rows, and `[name :execution intent]` sets an intent."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential [:sequential [:vector :seon.schema/value]]]] :seon.db/database-value]}
  [database txs]
  (reduce-kv (fn [value n steps]
               (db/with value (mapv (fn [[task-name end target]]
                                      (if (= :execution end)
                                        [:db/add [:seon.task/id (handle task-name)] :seon.task/execution target]
                                        (ended database task-name end target n)))
                                    steps)))
             database (vec txs)))

(defn- with-tree
  "Call `body` with the database holding the fifty-leaf tree, merge held, and the fixture agent."
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?]] :seon.schema/value]}
  [body]
  (test-support/with-database
   (fn [connection]
     (apply ingest! connection (tree-entries))
     (test-support/transacted! connection (test-support/agent-tx (db/db connection) "tq-tree-agent"))
     (test-support/transacted! connection
                               (into [] (comp (remove #{"merge"}) (map #(vector :db/add [:seon.task/id (handle %)] :seon.task/execution :running)))
                                     (concat ["design" "prove" "b" "integrate" "schema"] fan-out bodies)))
     (body (db/db connection)))))

(def ^:private done-through-prove [[["design" :done] ["prove" :done]]])

(def ^:private all-but-integrate
  (conj done-through-prove (mapv #(vector % :done) (concat fan-out ["schema"] bodies))))

(deftest ^{:seon/tasks #{'seon.task/the-ready-leaves-are-those-whose-needs-are-done}} the-ready-leaves-are-those-whose-needs-are-done
  (with-tree
   (fn [database]
     (are [txs expected] (= (set (map handle expected)) (task/ready (after database txs)))
       [] ["design"]
       [[["design" :done]]] ["prove"]
       done-through-prove (conj fan-out "schema")
       (conj done-through-prove [["schema" :done] ["f1" :done]]) (concat (rest fan-out) bodies)
       all-but-integrate ["integrate"]
       (conj all-but-integrate [["integrate" :done]]) []
       (conj all-but-integrate [["integrate" :done] ["merge" :execution :running]]) ["merge"]
       (conj done-through-prove [["f1" :sent-back "prove"]]) ["prove"]
       [[["design" :done] ["prove" nil]]] ["prove"]
       [[["design" :done] ["prove" :given-back]]] []
       (conj done-through-prove [["f1" :execution :stopped]]) (conj (vec (rest fan-out)) "schema")
       (conj all-but-integrate [["integrate" :done] ["merge" :execution :running]] [["merge" :done]]) [])
     (is (task/done? (after database all-but-integrate) (handle "b")) "a parent is done when its leaves are")
     (is (= {:seon.task/closed-by :seon.task/children} (task/resolution (after database all-but-integrate) (handle "b")))))))

(deftest ^{:seon/tasks #{'seon.task/the-ready-leaves-are-those-whose-needs-are-done}} an-ingested-task-is-held-and-not-ready
  (test-support/with-database
   (fn [connection]
     (ingest! connection (entry "a" "") (entry "b" (needs "a")))
     (let [intent #(:seon.task/execution (db/pull (db/db connection) [:seon.task/execution] [:seon.task/id (handle %)]))
           set-running (fn [n] (db/with (db/db connection) [[:db/add [:seon.task/id (handle n)] :seon.task/execution :running]]))]
       (is (= :held (intent "a")) "ingestion writes the intent")
       (is (= #{} (task/ready (db/db connection))) "a held task is no work")
       (is (= #{(handle "a")} (task/ready (set-running "a"))) "setting :running puts it in")
       (is (= #{} (task/ready (set-running "b"))) "unless a need blocks it")))))

(deftest ^{:seon/tasks #{'seon.task/the-ready-leaves-are-those-whose-needs-are-done}} ready-answers-in-under-10-ms-for-fifty-leaves
  (with-tree
   (fn [database]
     (let [value (after database (conj done-through-prove [["schema" :done] ["f1" :done]]))
           acquired (profile/measure #(task/ready value))
           measured (profile/measure #(task/ready value))]
       (is (= (:seon.profile/value acquired) (:seon.profile/value measured)))
       (is (= 44 (count (:seon.profile/value measured))))
       ;; The first call in a fresh JVM also plans the two queries; the budget is the warm read.
       (is (< (:seon.profile/ms measured) 10.0) "the two reads over fifty leaves: P1 measured 4 ms at worst")))))

(deftest ^{:seon/tasks #{'seon.task/an-attempt-is-written-once-with-its-agent}} an-attempt-is-written-once-with-its-agent
  (test-support/with-database
   (fn [connection]
     (ingest! connection (entry "a" ""))
     (let [h (handle "a")
           agent-id "tq-attempt-agent"
           created (db/with (db/db connection)
                            (into (test-support/agent-tx (db/db connection) agent-id)
                                  (task/attempt-tx (db/db connection) h agent-id :tq-attempt-1)))
           row #(db/pull %1 [:seon.task.attempt/number :seon.task/assigned :seon.task/branch
                             {:seon.task/attempt-of [:seon.task/id]} {:seon.task/agent [:seon.agent/id]}]
                         [:seon.task/assignment (id/id [h %2])])]
       (is (= {:seon.task.attempt/number 1 :seon.task/assigned h :seon.task/branch :tq-attempt-1
               :seon.task/attempt-of {:seon.task/id h} :seon.task/agent {:seon.agent/id agent-id}}
              (row created 1))
           "the attempt and the agent it names are one transaction; its identity is the task and attempt number")
       (is (= [] (task/attempt-tx created h agent-id :tq-attempt-1)) "a second write of the open attempt writes nothing")
       (let [closed (db/with created [[:db/add [:seon.task/assignment (id/id [h 1])] :seon.task.attempt/end :sent-back]])
             retried (db/with closed (task/attempt-tx closed h agent-id :tq-attempt-2))]
         (is (= 2 (:seon.task.attempt/number (row retried 2))) "after an end the next attempt is the next number")
         (is (= :tq-attempt-1 (:seon.task/branch (row retried 1))) "the ended attempt keeps its row"))))))
