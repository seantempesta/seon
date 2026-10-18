(ns seon.task-test
  "The task tree (runtime-follows-db §2): child tasks and needs as facts, the writer's
  needs-cycle refusal, the attempt and firing facts adopted on a branch of the live store."
  (:require [clj-kondo.core :as clj-kondo]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is are testing]]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.id :as id]
            [seon.cluster.registry :as registry]
            [seon.cluster.store]
            [seon.context :as context]
            [seon.profile :as profile]
            [seon.schema :as schema]
            [seon.step :as step]
            [seon.task :as task]
            [seon.test]
            [seon.test-support :as test-support]))

(def ^:private task-path "resources/seon/tasks/tq.tree.edn")

(defn- entry
  "One task EDN entry of tq.tree named `task-name` with the authored `members`."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [task-name members]
  (str "#:seon.task{:name \"" task-name "\" :plan \"Do " task-name ".\" " members "}"))

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
              (entry "body" (needs "prove")) (entry "body-own" (needs "prove-own"))
              (entry "prove-declared" ":step :prove :product #{tq.obligations/law tq.obligations/table}")
              (entry "body-declared" (needs "prove-declared"))
              (entry "subject" ":product #{tq.obligations/designed} :children #{[:seon.task/sym tq.tree/leaf] [:seon.task/sym tq.tree/plain]}")
              "#:seon.task{:name \"leaf\" :step :implement}" "#:seon.task{:name \"plain\"}")
     (doseq [[name tag] [['ordinary 'ordinary] ['implementation 'implement]
                       ['product 'body] ['own 'prove-own] ['product-own 'body-own] ['table 'body-declared]
                       ['goal 'subject] ['leaf-own 'leaf] ['plain-own 'plain]]]
       (test-support/declare-proof! connection 'tq.obligations name (symbol "tq.tree" (str tag))))
     (test-support/transacted! connection (test-support/agent-tx (db/db connection) "tq-obligations-agent"))
     (doseq [[name kind proofs product]
             [;; no step: its own tagged tests, never Implement's
              ["ordinary" nil '#{tq.obligations/ordinary} #{}]
              ["implement" :implement '#{tq.obligations/implementation} #{}]
              ["prove" :prove #{} '#{tq.obligations/product}]
              ["prove-own" :prove '#{tq.obligations/own} '#{tq.obligations/product-own}]
              ;; a declared product test no test holds yet is owed all the same
              ["prove-declared" :prove #{} '#{tq.obligations/law tq.obligations/table}]
              ;; an explicit Implement step adds its subject's tagged tests and its effective product
              ["leaf" :implement '#{tq.obligations/leaf-own tq.obligations/goal tq.obligations/designed} '#{tq.obligations/designed}]
              ;; a step with none inherits the product, never the subject's tests
              ["plain" nil '#{tq.obligations/plain-own} '#{tq.obligations/designed}]]]
       (is (= (cond-> {:seon.task/proofs proofs :seon.task/product product} kind (assoc :seon.task/step kind))
              (task/obligations (db/db connection) (symbol "tq.tree" name)))
           name)
       (is (= proofs (:seon.task/proofs
                      (task/assignment-read (db/db connection) (task/task-handle 'tq.tree name)
                                            [:seon.agent/id "tq-obligations-agent"])))
           name))
     (is (= {:seon.task/proofs #{} :seon.task/product #{}}
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
     (let [handle (task/task-handle 'tq.tree "a")
           assignment (id/id [handle "tq-tree-agent" :tq-tree])
           firing (id/id [handle #inst "2026-10-11T00:00:00Z"])
           commit (random-uuid)]
       (test-support/transacted!
        connection
        [{:db/id [:seon.task/id handle] :seon.task/step :prove :seon.task/execution :held
          :seon.task/cron "0 3 * * *" :seon.task/zone "UTC" :seon.task/function [:seon.fn/sym 'seon.task/create!]}
         {:seon.task/assignment assignment :seon.task/assigned handle :seon.task/attempt-of [:seon.task/id handle]
          :seon.task/revision (task/task-revision (db/db connection) handle) :seon.task/agent [:seon.agent/id "tq-tree-agent"]
          :seon.task/branch :tq-tree :seon.task.attempt/end :error
          :seon.task.attempt/target [:seon.task/id handle]
          :seon.task.attempt/reason "The proof asserts the old shape." :seon.task.attempt/commit commit}
         {:seon.task.fire/id firing :seon.task.fire/at #inst "2026-10-11T00:00:00Z" :seon.task.fire/of [:seon.task/id handle]
          :seon.task.fire/to [:seon.agent/id "tq-tree-agent"]}])
       (let [database (db/db connection)
             sym-of #(:seon.task/sym (db/pull database [:seon.task/sym] (:db/id %)))]
         (is (= {:seon.task/children #{'tq.tree/a}}
                (update-vals (db/pull database [:seon.task/children] [:seon.task/sym 'tq.tree/p]) #(set (map sym-of %)))))
         (is (= {:seon.task/needs #{'tq.tree/p}}
                (update-vals (db/pull database [:seon.task/needs] [:seon.task/sym 'tq.tree/b]) #(set (map sym-of %)))))
         (is (= {:seon.task/step :prove :seon.task/execution :held :seon.task/cron "0 3 * * *" :seon.task/zone "UTC"}
                (db/pull database [:seon.task/step :seon.task/execution :seon.task/cron :seon.task/zone] [:seon.task/id handle])))
         (is (= 'seon.task/create!
                (:seon.fn/sym (:seon.task/function (db/pull database [{:seon.task/function [:seon.fn/sym]}] [:seon.task/id handle])))))
         (is (= {:seon.task.attempt/end :error
                 :seon.task.attempt/reason "The proof asserts the old shape." :seon.task.attempt/commit commit
                 :seon.task/attempt-of 'tq.tree/a :seon.task.attempt/target 'tq.tree/a}
                (-> (db/pull database [:seon.task.attempt/end
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
  (task/task-handle 'tq.tree task-name))

(defn- ended
  "An attempt row of tq.tree/`task-name` by the fixture agent, ended `end` (open when nil), sent
  back to `target` when given; `n` keeps a task's second attempt distinct. `:merged` is a :done end
  whose merge reached main (`:seon.task/outcome` `:merged`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :string [:maybe [:or :seon.task.attempt/end [:= :merged]]] [:maybe :string] :int] :map]}
  [database task-name end target n]
  (cond-> {:seon.task/assignment (id/id [(handle task-name) "tq-tree-agent" n]) :seon.task/assigned (handle task-name)
           :seon.task/attempt-of [:seon.task/id (handle task-name)] :seon.task/revision (task/task-revision database (handle task-name))
           :seon.task/agent [:seon.agent/id "tq-tree-agent"] :seon.task/branch (keyword (str "tq-" task-name "-" n))}
    (= :merged end) (assoc :seon.task.attempt/end :done :seon.task/outcome :merged)
    (and end (not= :merged end)) (assoc :seon.task.attempt/end end)
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

(def ^:private done-through-prove [[["design" :merged] ["prove" :merged]]])

(def ^:private all-but-integrate
  (conj done-through-prove (mapv #(vector % :merged) (concat fan-out ["schema"] bodies))))

(deftest ^{:seon/tasks #{'seon.task/the-ready-leaves-are-those-whose-needs-are-done}} the-ready-leaves-are-those-whose-needs-are-done
  (with-tree
   (fn [database]
     (are [txs expected] (= (set (map handle expected)) (task/ready (after database txs)))
       [] ["design"]
       [[["design" :merged]]] ["prove"]
       done-through-prove (conj fan-out "schema")
       (conj done-through-prove [["schema" :merged] ["f1" :merged]]) (concat (rest fan-out) bodies)
       all-but-integrate ["integrate"]
       (conj all-but-integrate [["integrate" :merged]]) []
       (conj all-but-integrate [["integrate" :merged] ["merge" :execution :running]]) ["merge"]
       (conj done-through-prove [["f1" :sent-back "prove"]]) ["prove"]
       [[["design" :merged] ["prove" nil]]] ["prove"]
       [[["design" :merged] ["prove" :given-back]]] []
       (conj done-through-prove [["f1" :execution :stopped]]) (conj (vec (rest fan-out)) "schema")
       (conj all-but-integrate [["integrate" :merged] ["merge" :execution :running]] [["merge" :merged]]) [])
     (is (not (contains? (task/ready (after database [[["design" :done]]])) (handle "prove")))
         "a need ended :done whose merge has not reached main leaves its successor unready")
     (is (not (task/done? (after database [[["design" :done]]]) (handle "design"))) "done is merged, not ended")
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
     (let [value (after database (conj done-through-prove [["schema" :merged] ["f1" :merged]]))
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

(defn- adopted-file
  "The tq.adopt task file declaring `area` and `side` with the intents given."
  {:malli/schema [:=> [:cat :string :string] :seon.task/files]}
  [area side]
  {"resources/seon/tasks/tq.adopt.edn"
   (str "[#:seon.task{:name \"area\" :plan \"" area "\"} #:seon.task{:name \"side\" :plan \"" side "\"}]")})

(deftest an-adoption-keeps-a-merged-refinement-the-files-have-not-seen
  ;; Before, adoption reconciled the whole set onto main with main's own value as the basis, so the
  ;; next publication reverted a refinement merged onto main whose publication still waited.
  (let [area (task/task-handle 'tq.adopt "area")
        side (task/task-handle 'tq.adopt "side")
        intents (fn [database] (mapv #(:seon.task/plan (db/pull database [:seon.task/plan] [:seon.task/id %])) [area side]))
        problems (fn [database] (count (db/q '[:find [?n ...] :where [?t :seon.task/ns seon.task] [?t :seon.task/name ?n]
                                                [(clojure.string/starts-with? ?n "adoption-conflict-")]] database)))]
    (are [published adopted conflicts]
         (test-support/with-database
          (fn [source]
            (test-support/with-database
             (fn [main]
               (let [file! (fn [connection files] (db/with-branch {:seon.db/connection connection}
                                                    #(task/index-tasks! {:seon.db/connection connection :seon.task/files files})))
                     _ (file! source (adopted-file "Fix." "Other."))
                     base @source
                     ;; root, who receives the problem task
                     _ (test-support/transacted! main (test-support/agents-tx @main ["root"]))
                     _ (file! main (adopted-file "Fix." "Other."))
                     _ (file! main (adopted-file "Refined." "Other."))
                     before (problems @main)
                     _ (file! source published)
                     call (task/adopt-tasks-call @source @main base)]
                 (when (seq call) (test-support/transacted! main call))
                 (and (is (= adopted (intents @main)))
                      (is (= conflicts (- (problems @main) before)))))))))
      ;; an unchanged republication adopts nothing
      (adopted-file "Fix." "Other.") ["Refined." "Other."] 0
      ;; a republication changing only side takes side and keeps main's area
      (adopted-file "Fix." "Sides.") ["Refined." "Sides."] 0
      ;; a republication changing area too keeps main's area and writes one problem task for root
      (adopted-file "Squared." "Sides.") ["Refined." "Sides."] 1)))

;;; CHAIN: a subject with a Prove and an Implement step, authored sparse in one file (hybrid-chains §4.1).

(defn- chain-entries
  "The tq.tree chain: the subject's `goal`, Prove's instructions and Implement's `own` members."
  {:malli/schema [:=> [:cat :string :string] [:vector :string]]}
  [goal own]
  [(str "#:seon.task{:name \"subject\" :plan \"" goal "\" " (children ["prove" "implement"]) "}")
   "#:seon.task{:name \"prove\" :step :prove :instructions \"Add rows to `tq.tree-test/rows`.\"}"
   (str "#:seon.task{:name \"implement\" " (needs "prove") " :instructions \"Fix `tq.tree/scan`.\" " own "}")])

(deftest a-steps-effective-task-is-its-subjects-merged-with-its-own
  (test-support/with-database
   (fn [connection]
     (apply ingest! connection (chain-entries "Make `tq.tree/scan` idempotent." ":plan \"Only the body.\""))
     (let [content #(select-keys (task/effective (db/db connection) (handle %)) [:seon.task/plan :seon.task/instructions])]
       (are [step expected] (= expected (content step))
         "subject" {:seon.task/plan "Make `tq.tree/scan` idempotent."}
         "prove" {:seon.task/plan "Make `tq.tree/scan` idempotent." :seon.task/instructions "Add rows to `tq.tree-test/rows`."}
         "implement" {:seon.task/plan "Only the body." :seon.task/instructions "Fix `tq.tree/scan`."})
       (is (= #{'tq.tree-test/rows}
              (set (:seon.task/references (db/pull (db/db connection) [:seon.task/references] [:seon.task/id (handle "prove")]))))
           "the writer derives references from the instructions too")
       (apply ingest! connection (chain-entries "Make `tq.tree/scan` total." ""))
       (is (= "Make `tq.tree/scan` total." (:seon.task/plan (content "implement")))
           "a refined subject intent is the step's next view once its own intent is gone")))))

(deftest a-running-subject-readies-its-first-step-only
  (test-support/with-database
   (fn [connection]
     (apply ingest! connection (chain-entries "Make `tq.tree/scan` idempotent." ""))
     (test-support/transacted! connection (test-support/agent-tx (db/db connection) "tq-tree-agent"))
     (let [execution #(:seon.task/execution (db/pull (db/db connection) [:seon.task/execution] [:seon.task/id (handle %)]))
           running (after (db/db connection) [[["subject" :execution :running]]])]
       (is (= [:held nil nil] (mapv execution ["subject" "prove" "implement"])) "ingestion holds the root only")
       (is (= #{} (task/ready (db/db connection))) "a held subject holds its steps")
       (is (= #{(handle "prove")} (task/ready running)) "one fact on the subject launches its first step")
       (is (= :running (task/execution running (handle "implement"))) "a step runs under its subject's execution")
       (is (= #{(handle "implement")} (task/ready (after running [[["prove" :merged]]]))))
       (is (= #{} (task/ready (after running [[["implement" :execution :held]] [["prove" :merged]]])))
           "a step's own :held keeps it held")))))

(deftest ^{:seon/tasks #{'seon.task/chain-obligations}}
  one-selector-serves-every-proof-consumer
  ;; TASK-CHAIN A1 (`docs/seon/schedule/cards/TASK-CHAIN.md#interfaces`): one selector over main, candidate and base
  (test-support/with-database
   (fn [connection]
     (ingest! connection (entry "fix" ""))
     (let [obligations (requiring-resolve 'seon.task/obligations)
           gate (fn [tagged] (test-support/declare-source!
                              connection 'tq.gates
                              (str "(ns tq.gates (:require [clojure.test :refer [deftest is]]))\n"
                                   (str/join (for [t '[g h]]
                                               (str "(deftest " (when (tagged t) "^{:seon/tasks #{'tq.tree/fix}} ") t " (is (= 1 2)))\n"))))))
           _ (gate #{'g})
           base (db/db connection)
           ;; the candidate removes G's tag and adds H
           _ (gate #{'h})
           candidate (db/db connection)
           ;; root removes G on main
           _ (gate #{})
           moved (db/db connection)]
       (is (= '#{tq.gates/g tq.gates/h} (:seon.task/proofs (obligations base candidate base 'tq.tree/fix)))
           "main still requires G and the candidate adds H")
       (is (= '#{tq.gates/h} (:seon.task/proofs (obligations moved candidate base 'tq.tree/fix)))
           "root's removal on main applies; the base's inherited gate is no candidate addition")
       (is (= (task/obligations candidate 'tq.tree/fix) (obligations candidate candidate candidate 'tq.tree/fix))
           "the two-argument arity is the three-basis one over one value"))))
  (testing "census: only the selector reads tags to select proofs"
    (let [allowed '#{seon.task/obligations seon.fn/task-links seon.task/open-tasks
                     seon.task/task-findings seon.task/task-edges seon.cluster.agent/render-identity-html}
          readers (db/q '[:find [?s ...] :where [?e :seon.fn/keywords :seon.task/tasks] [?e :seon.fn/sym ?s]] (db/db))]
      (is (= #{} (into #{} (remove #(or (allowed %) (str/ends-with? (namespace %) "-test"))) readers))
          "writers, links, dangling, triage, task-edges' subjects and namespace tag display read tags for their own purpose"))))

(deftest only-a-merge-completes-a-leaf
  ;; TASK-CHAIN phase 4: :satisfied is deleted; an empty delta merges as an accepted no-op
  (let [projection (schema/handed-projection)]
    (doseq [attribute [:seon.task.attempt/end :seon.task/end :seon.task/status]]
      (is (not ((schema/projection-validator projection attribute) :satisfied)) "no end or status admits :satisfied")))
  (is (not-any? #{:satisfied} (flatten task/ready-rules)) "a leaf is done only by its merged outcome"))

(deftest task-members-have-defaults-and-kinds
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           entity (fn [task-name members]
                    (merge {:seon.task/id (id/id ['tq.kinds task-name]) :seon.task/sym (symbol "tq.kinds" task-name)
                            :seon.task/ns 'tq.kinds :seon.task/name task-name :seon.task/plan "Do it."}
                           members))
           executions (fn [tx] (set (keep :seon.task/execution (filter map? tx))))
           running (task/adopt-tasks-tx database [(entity "a" {:seon.task/execution :running})]
                                        #{(task/task-handle 'tq.kinds "a")} {})]
       (is (= {:seon.task/execution :held} (task/task-defaults (db/carried-projection database)))
           "decoding {} fills the execution default")
       (is (= #{:held} (executions (task/adopt-tasks-tx database [(entity "b" {})] #{(task/task-handle 'tq.kinds "b")} {})))
           "a new task takes the default")
       (is (= #{:running} (-> (db/with database running) (db/pull [:seon.task/execution] [:seon.task/sym 'tq.kinds/a])
                              :seon.task/execution hash-set))
           "a supplied execution survives the writer")
       (let [started (db/with database running)
             edited (task/adopt-tasks-tx started [(entity "a" {:seon.task/execution :running :seon.task/plan "Do it better."})]
                                         #{(task/task-handle 'tq.kinds "a")}
                                         {(task/task-handle 'tq.kinds "a") (task/task-declaration started (task/task-handle 'tq.kinds "a"))})]
         (is (= :running (:seon.task/execution (db/pull (db/with started edited) [:seon.task/execution] [:seon.task/sym 'tq.kinds/a])))
             "editing a running task never resets it held"))
       (is (:seon.error/at (db/transact! connection [(entity "x y" {})])) "a name that is no symbol name is refused by the writer")))))

(deftest owned-members-are-instructions-and-proofs
  (test-support/with-database
   (fn [connection]
     (let [registry (:seon.schema.projection/registry (db/carried-projection (db/db connection)))
           members-of (fn [k] (into {} (m/entries (m/deref (mr/schema registry k)))))
           task (members-of :seon.task/task)
           owned (into #{} (filter #(:seon.task/owned (m/properties (mr/schema registry %)))) (keys task))]
       (is (= #{:seon.task/instructions :seon.task/proofs :seon.task/model} owned)
           "the schema property marks exactly instructions, proofs and model")
       (is (contains? task :seon.task/next) "a task may link its next")
       (is (not-any? (members-of :seon.task/declaration) [:seon.task/next :seon.task/execution])
           "next and execution are never declared")))))

(deftest the-runnable-schema-names-every-dial-the-launch-reads
  ;; reactive-start plan 2: the desired config is checked against its readers, never trusted. A dial is read by
  ;; keyword in the turn loop, the provider, the agent's settings and the launch, or generically by seon.ai through
  ;; its :seon.ai/request-attribute; the three cluster dials root reads from main are no chain's entry.
  (test-support/with-database
   (fn [connection]
     (let [registry (:seon.schema.projection/registry (db/carried-projection (db/db connection)))
           dial? #(and (qualified-keyword? %) (str/starts-with? (namespace %) "seon.config."))
           read-by-keyword (into #{}
                                 (comp (map #(keyword (str (:ns %)) (:name %))) (filter dial?))
                                 (:keywords (:analysis (clj-kondo/run!
                                                        {:lint (mapv #(.getPath (io/resource %))
                                                                     ["seon/turn.clj" "seon/ai.clj" "seon/agent.clj"
                                                                      "seon/cluster/agent.clj"])
                                                         :cache false
                                                         :config {:output {:canonical-paths true}
                                                                  :analysis {:keywords true}}}))))
           read-generically (into #{} (filter #(and (dial? %) (:seon.ai/request-attribute (m/properties (mr/schema registry %)))))
                                  (keys (mr/schemas registry)))
           cluster-dials #{:seon.config.agent/max-running :seon.config.agent/keep-ended-branches?}
           declared (into #{} (map first) (m/entries (m/deref (mr/schema registry :seon.task/desired-config))))]
       ;; keep-ended-branches? is read through its one reader, seon.config/keeps-ended-branches? (KEEP-BRANCHES)
       (is (every? (into read-by-keyword
                         (comp (map #(keyword (str (:ns %)) (:name %))) (filter cluster-dials))
                         (:keywords (:analysis (clj-kondo/run! {:lint [(.getPath (io/resource "seon/config.clj"))]
                                                                :cache false
                                                                :config {:output {:canonical-paths true}
                                                                         :analysis {:keywords true}}}))))
                   cluster-dials)
           "the cluster dials are read where the launch reads, or by their reader in seon.config")
       (is (= (into (apply disj read-by-keyword cluster-dials) read-generically) declared)
           "the desired config declares exactly the dials read on the chain branch")
       (is (every? #(get-in (m/properties (mr/schema registry %)) [:seon.config/dial]) declared)
           "every entry is a registered dial")))))

(deftest three-tasks-with-tempids-are-written-linked-in-one-transaction
  ;; TASK-CHAIN S1c-1 (`docs/seon/schedule/cards/TASK-CHAIN.md#one-writer-for-tasks`)
  (test-support/with-database
   (fn [connection]
     (let [handle #(task/task-handle 'tq.link %)
           write! (fn [tasks] (test-support/transacted! connection
                                                        (task/adopt-tasks-tx (db/db connection) tasks
                                                                             (into #{} (map (comp handle :seon.task/name)) tasks) {})))
           _ (write! [{:seon.task/ns 'tq.link :seon.task/name "template" :seon.task/plan "A worked example."
                       :seon.task/instructions "Check with `seon.task/template`." :seon.task/proofs '#{tq.link/proof}}])
           owned (task/template (db/db connection) 'tq.link/template)
           report (write! [(merge owned {:db/id -1 :seon.task/ns 'tq.link :seon.task/name "fix" :seon.task/next -2
                                         :seon.task/plan "Fix `seon.task/task-handle`." :seon.task/execution :running})
                           (merge owned {:db/id -2 :seon.task/ns 'tq.link :seon.task/name "fix-2" :seon.task/next -3})
                           (merge owned {:db/id -3 :seon.task/ns 'tq.link :seon.task/name "fix-3"})])
           database (db/db connection)
           pulled (fn [task-name] (db/pull database [:seon.task/sym :seon.task/execution :seon.task/references :seon.task/plan
                                                     {:seon.task/next [:seon.task/id]}]
                                           [:seon.task/id (handle task-name)]))]
       (is (= {:seon.task/instructions "Check with `seon.task/template`." :seon.task/proofs '#{tq.link/proof}} owned)
           "a template answers exactly its owned members")
       (is (= 1 (count (into #{} (comp (filter #(= :seon.task/id (:a %))) (map :tx)) (:tx-data report))))
           "the three identities are asserted in one transaction")
       (is (= [(handle "fix-2") (handle "fix-3") nil] (mapv #(get-in (pulled %) [:seon.task/next :seon.task/id]) ["fix" "fix-2" "fix-3"]))
           "each task's next is the task its tempid named")
       (is (= '[tq.link/fix tq.link/fix-2 tq.link/fix-3] (mapv (comp :seon.task/sym pulled) ["fix" "fix-2" "fix-3"]))
           "identity and symbol derive from ns and name")
       (is (= [:running :held :held] (mapv (comp :seon.task/execution pulled) ["fix" "fix-2" "fix-3"]))
           "a supplied execution stays; the rest take the default")
       (is (= ['#{seon.task/task-handle seon.task/template} '#{seon.task/template}]
              (mapv (comp set :seon.task/references pulled) ["fix" "fix-2"]))
           "the writer derives each task's references from its plan and instructions")
       (is (nil? (:seon.task/plan (pulled "fix-2"))) "a template carries no plan")
       (is (= :unknown-task (:seon.task/refused (task/template database 'tq.link/absent))) "an absent template is refused by name")))))

(deftest a-chain-is-linked-tasks-written-in-one-transaction-on-its-branch
  ;; TASK-CHAIN S1c-2 (`docs/seon/schedule/cards/TASK-CHAIN.md#2-flow`, Create)
  (test-support/with-database
   (fn [connection]
     (let [store (:seon.store/store (test-support/execution-handle connection))
           context {:seon.db/connection connection :seon.store/store store}
           ;; chain tasks are root's runtime tasks (`:seon.task/created-by`): a cluster's root agent
           _ (test-support/transacted! connection (test-support/agent-tx (db/db connection) "root"))
           chain! (requiring-resolve 'seon.task/chain!)
           main-branch (get-in (db/db connection) [:config :branch])
           handle #(task/task-handle 'tq.chain %)
           _ (test-support/transacted! connection
                                       (task/adopt-tasks-tx (db/db connection)
                                                            [{:seon.task/ns 'tq.chain :seon.task/name "template"
                                                              :seon.task/instructions "Check with `seon.task/chain!`."
                                                              :seon.task/proofs '#{tq.chain/proof}}]
                                                            #{(handle "template")} {}))
           owned (task/template (db/db connection) 'tq.chain/template)
           plan "Link the tasks of `seon.task/chain!`."
           maps [(merge owned {:seon.task/ns 'tq.chain :seon.task/name "fix" :seon.task/plan plan})
                 ;; identity, symbol, tempid and next in a later map are ignored
                 (merge owned {:db/id -7 :seon.task/ns 'tq.other :seon.task/name "ignored" :seon.task/id (handle "fix")
                               :seon.task/sym 'tq.chain/fix :seon.task/next -1})
                 owned]
           measured (profile/measure #(chain! context maps))
           answer (:seon.profile/value measured)
           branch (:seon.chain/branch answer)
           chain-db #(db/branch-database (db/db connection) branch)
           names ["fix" "fix-2" "fix-3"]
           pulled (fn [database task-name]
                    (db/pull database [:seon.task/plan :seon.task/instructions :seon.task/proofs :seon.task/execution
                                       {:seon.task/next [:seon.task/name]}]
                             [:seon.task/id (handle task-name)]))
           commits (fn [] [(db/commit-id (db/db connection)) (db/commit-id (chain-db))])
           from-branch (keyword (str "chain-" (handle "again")))]
       (try
         (is (= {:seon.chain/branch (keyword (str "chain-" (handle "fix"))) :seon.chain/first (handle "fix")
                 :seon.task/handles (mapv handle names)
                 :seon.agent/id (task/attempt-agent-id (id/id [(handle "fix") 1]))}
                answer)
             "the answer names the branch, the first task, every task in chain order and its first attempt's agent")
         (is (contains? (registry/branches store) branch) "the chain's branch exists")
         (let [on-branch (chain-db)
               tasks (mapv #(pulled on-branch %) names)]
           (is (= ["fix-2" "fix-3" nil] (mapv #(get-in % [:seon.task/next :seon.task/name]) tasks))
               "each task's next is the one after it, by position")
           (is (= 1 (count (db/q '[:find [?tx ...] :in $ [?h ...] :where [?e :seon.task/id ?h ?tx]] on-branch (mapv handle names))))
               "every task identity is asserted in one transaction")
           (is (every? #(= owned (-> % (select-keys [:seon.task/instructions :seon.task/proofs]) (update :seon.task/proofs set))) tasks)
               "each task carries its template's owned members")
           (is (= [plan nil nil] (mapv :seon.task/plan tasks)) "only the first map supplied a plan")
           (is (= [:running :held :held] (mapv :seon.task/execution tasks)) "the first runs, the rest are held"))
         (let [main (db/db connection)]
           (is (not-any? #(:seon.task/execution (pulled main %)) names) "main holds no chain task")
           (is (empty? (db/q '[:find [?a ...] :in $ [?h ...] :where [?a :seon.task/assigned ?h]] main (mapv handle names)))
               "main holds no attempt of a chain task")
           (is (= {:seon.chain/branch branch} (db/pull main [:seon.chain/branch] [:seon.chain/first (handle "fix")]))
               "main holds the chain fact"))
         (let [edited "An edited plan."
               opened (seon.cluster.store/open-branch! store branch)
               _ (try (test-support/transacted! opened [[:db/add [:seon.task/id (handle "fix")] :seon.task/plan edited]])
                      (finally (seon.cluster.store/release-branch! opened)))
               before (commits)]
           (is (= answer (chain! context maps)) "a repeat answers the existing chain")
           (is (= before (commits)) "a repeat commits nothing on main or the branch")
           (is (= edited (:seon.task/plan (pulled (chain-db) "fix"))) "a repeat keeps the edited plan"))
         (let [from (chain! context [(assoc owned :seon.task/ns 'tq.chain :seon.task/name "again" :seon.task/plan plan)] {:seon.chain/from branch})
               _ (is (= from-branch (:seon.chain/branch from)) "a chain's branch is named by its first task")
               database (db/db connection)]
           (is (= main-branch (:seon.branch/target (registry/fork-of (db/branch-database database from-branch)
                                                                      from-branch)))
               "a chain started from another branch merges toward main")
           (is (= (registry/fork-point database (registry/head-commit-id database branch) main-branch)
                  (registry/fork-point database (registry/head-commit-id database from-branch) main-branch))
               "its fork point is the main commit the first chain forked at")
           (is (= :running (:seon.task/execution (pulled (db/branch-database database from-branch) "fix")))
               "it starts from that branch's head, holding its tasks"))
         (is (< (:seon.profile/ms measured) 1000) "the durable create is measured, expected about 100 ms")
         (finally
           (doseq [b [branch from-branch] :when (contains? (registry/branches store) b)]
             (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))))))))

(deftest a-chain-whose-config-names-a-rejected-dial-value-is-refused
  ;; reactive-start plan §3: nothing executes until its desired state validates. `seon.task/chain!` validates each
  ;; running task's `:seon.task/runnable` view before it forks, and a refusal carries Malli's explanation as data
  (test-support/with-database
   (fn [connection]
     (let [store (:seon.store/store (test-support/execution-handle connection))
           context {:seon.db/connection connection :seon.store/store store}
           _ (test-support/transacted! connection (test-support/agent-tx (db/db connection) "root"))
           task {:seon.task/ns 'tq.unrun :seon.task/name "fix" :seon.task/plan "Fix it." :seon.task/proofs '#{tq.unrun/proof}}]
       (doseq [[label tasks options explained]
               [["a dial value its schema rejects" [task] {:seon.chain/config {:seon.config.ai/max-tokens 0}}
                 [:seon.task/desired-config :seon.config.ai/max-tokens]]
                ["a dial no chain branch reads" [task] {:seon.chain/config {:seon.config.agent/max-running 2}}
                 [:seon.task/desired-config :seon.config.agent/max-running]]
                ["a running task with no proofs" [(dissoc task :seon.task/proofs)] {} [:seon.task/proofs]]]]
         (let [before [(db/commit-id (db/db connection)) (registry/branches store)]
               refusal (task/chain! context tasks options)]
           (is (= :unrunnable (:seon.task/refused refusal)) label)
           (is (some? (get-in (:seon.task/explanation refusal) explained)) "the explanation names the offending key")
           (is (= before [(db/commit-id (db/db connection)) (registry/branches store)])
               "nothing is written: no fork, no task, no chain fact")))))))

(deftest a-complete-chain-is-written
  ;; reactive-start plan §3: a chain whose running tasks validate is written as given, its execution honored, so a
  ;; chain written :held starts nothing until root runs it
  (test-support/with-database
   (fn [connection]
     (let [store (:seon.store/store (test-support/execution-handle connection))
           _ (test-support/transacted! connection (test-support/agent-tx (db/db connection) "root"))
           {branch :seon.chain/branch [first-task] :seon.task/handles}
           (task/chain! {:seon.db/connection connection :seon.store/store store}
                        [{:seon.task/ns 'tq.complete :seon.task/name "fix" :seon.task/plan "Fix it."
                          :seon.task/proofs '#{tq.complete/proof} :seon.task/execution :held}]
                        {:seon.chain/config {:seon.config.ai/max-tokens 512}})]
       (try
         (is (contains? (registry/branches store) branch) "the chain's branch exists")
         (is (= :held (:seon.task/execution (db/pull (db/branch-database (db/db connection) branch)
                                                     [:seon.task/execution] [:seon.task/id first-task])))
             "the written execution is honored")
         (finally (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})))))))

(def ^:private chain-proofs
  ;; A template proof reading its task's plan, a successor proof, a handoff reading the successor's result on the
  ;; supplied branch, and a tagged reader run with no task.
  (str "(ns tq.chainproof (:require [clojure.string :as str] [clojure.test :refer [deftest is]] [seon.step :as step] [seon.test]))\n"
       "(deftest planned (is (str/includes? (get-in (seon.test/task) [:seon.test/task :seon.task/plan] \"\") \"## Approach\")))\n"
       "(deftest successor (is (= 1 1)))\n"
       "(deftest handoff (let [{branch :seon.test/branch} (seon.test/task)]\n"
       "  (is (= 1 (:seon.test/pass-count (get (step/results branch '[tq.chainproof/successor]) 'tq.chainproof/successor))))))\n"
       "(deftest ^{:seon/tasks #{'tq.chain/a}} unbound (is (nil? (seon.test/task))))\n"
       ;; a task reader whose body runs a definition outside its static closure (a resolved, unquoted name)
       "(defn helper {:malli/schema [:=> [:cat] :int]} [] 1)\n"
       "(deftest dynamic (is (some? (seon.test/task))) (is (= 2 ((resolve (symbol \"tq.chainproof\" \"helper\"))))))\n"))

(defn- task-run!
  "Run the tests `identities` on `connection` judging `task` (nil: no task), as an agent's test step does."
  {:malli/schema [:=> [:cat :seon.db/connection [:set :symbol] [:maybe :seon.task/sym]] :map]}
  [connection identities task]
  (let [ran (seon.test/run (cond-> {:seon.test/execution (test-support/context-source connection)
                                    :seon.test/recording-connection connection :seon.test/policy :named
                                    :seon.test/identities identities}
                             task (assoc :seon.test/task task)))]
    (when (:seon.error/at ran) (throw (ex-info (:seon.error/message ran) ran)))
    ran))

(defn- outcome
  "`test-symbol`'s recorded outcome on `connection`'s head under `task`'s content (`seon.step/results`; nil: no task):
  :green, :red or absent."
  {:malli/schema [:=> [:cat :seon.db/connection :symbol [:maybe :seon.task/sym]] [:maybe [:enum :green :red]]]}
  [connection test-symbol task]
  (some-> (get (if task (step/results (db/db connection) [test-symbol] task) (step/results (db/db connection) [test-symbol])) test-symbol)
          (#(if (pos? (+ (:seon.test/fail-count %) (:seon.test/error-count %))) :red :green))))

(deftest a-task-reading-result-is-recorded-at-the-reach-its-readers-compute
  ;; S5 run 2 D4 (`docs/research/agent-platform/2026-10-14/s5-run-2.md`): the recorder keyed a task reader's result with
  ;; the definitions its body ran outside its closure, the readers without, so the proof never read as proved
  (test-support/with-database
   (fn [connection]
     (let [dynamic 'tq.chainproof/dynamic]
       (test-support/task-input connection [{:db/id -1 :seon.task/ns 'tq.chain :seon.task/name "a" :seon.task/plan "## Problem\n\nP."
                                             :seon.task/proofs #{dynamic}}] 'tq.chain/a)
       (test-support/declare-source! connection 'tq.chainproof chain-proofs)
       (is (= 1 (:seon.test/executed-count (task-run! connection #{dynamic} 'tq.chain/a))) "the proof runs once under its task")
       (is (= :red (outcome connection dynamic 'tq.chain/a)) "its red result reads back under the task it was recorded for")
       (is (= [dynamic] (mapv :seon.test/sym (context/current-results (db/db connection) #{dynamic} 'tq.chain/a)))
           "the done rule's reader finds it at the identity it was recorded under")
       (is (nil? (outcome connection dynamic nil)) "and it answers for no task-less read")
       (is (zero? (:seon.test/executed-count (task-run! connection #{dynamic} 'tq.chain/a)))
           "the same input proves once: the next request reuses it")))))

(deftest a-task-reading-result-is-current-on-every-value-of-its-content
  ;; S5 pre-mortem Findings 2 and 5 (`docs/research/agent-platform/2026-10-14/premortem-s5.md`): a task reader's
  ;; identity hashed connection-local cache state, and `seon.test/stale` recomputed it with no task or dynamic calls
  (test-support/with-database
   (fn [connection]
     (let [dynamic 'tq.chainproof/dynamic
           store (:seon.store/store (test-support/execution-handle connection))
           branch (keyword (str "identity-" (random-uuid)))
           current #(mapv :seon.test/sym (context/current-results % #{dynamic} 'tq.chain/a))]
       (test-support/task-input connection [{:db/id -1 :seon.task/ns 'tq.chain :seon.task/name "a" :seon.task/plan "## Problem\n\nP."
                                             :seon.task/proofs #{dynamic}}] 'tq.chain/a)
       (test-support/declare-source! connection 'tq.chainproof chain-proofs)
       (task-run! connection #{dynamic} 'tq.chain/a)
       (let [database (db/db connection)]
         (is (= [dynamic] (current database)) "the recording connection reads it current")
         (is (= [] (seon.test/stale database [dynamic] 'tq.chain/a)) "the proof is not stale when nothing changed"))
       ;; another task is no declaration of the program, nor this task's content
       (test-support/task-input connection [{:db/id -1 :seon.task/ns 'tq.chain :seon.task/name "other" :seon.task/plan "## Problem\n\nQ."}]
                                'tq.chain/other)
       (is (= [dynamic] (current (db/db connection))) "an unrelated write leaves it current")
       (is (= [] (seon.test/stale (db/db connection) [dynamic] 'tq.chain/a)) "and not stale")
       (is (zero? (:seon.test/executed-count (task-run! connection #{dynamic} 'tq.chain/a))) "so it is not run again")
       (try
         (registry/branch! {:seon.store/store store :seon.cluster.registry/from (get-in (db/db connection) [:config :branch])
                            :seon.store/branch branch})
         (let [opened (seon.cluster.store/open-branch! store branch)]
           (try (is (= [dynamic] (current (db/db opened))) "another connection to the same content reads it current")
                (finally (seon.cluster.store/release-branch! opened))))
         (is (= [dynamic] (current (db/branch-database (db/db connection) branch))) "the branch head opened from main reads it current")
         (finally (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})))))))

(deftest a-tasks-revision-is-its-content-on-an-as-of-view
  ;; Lane identity-stable (064809752): `seon.task/task-revision` answered nil on an as-of view of the very basis
  ;; that holds the task, so a task reader's result read back as not current there
  (test-support/with-database
   (fn [connection]
     (test-support/task-input connection [{:db/id -1 :seon.task/ns 'tq.chain :seon.task/name "a" :seon.task/plan "## Problem\n\nP."}]
                              'tq.chain/a)
     (let [database (db/db connection)
           handle (task/task-handle 'tq.chain "a")]
       (is (string? (task/task-revision database handle)) "the current value answers the task's revision")
       (is (= (task/task-revision database handle) (task/task-revision (db/as-of database (db/basis-t database)) handle))
           "an as-of view of the same basis answers the same revision")))))

(deftest a-task-write-resolves-every-tasks-branch
  ;; task-model T1 (`docs/research/agent-platform/2026-10-17/task-model.md#4-attributes`): one write of linked tasks,
  ;; every task :held, each `:seon.task/branch` resolved once at the write
  (test-support/with-database
   (fn [connection]
     (let [_ (test-support/transacted! connection (test-support/agent-tx (db/db connection) "root"))
           main-branch (get-in (db/db connection) [:config :branch])
           handle #(task/task-handle 'tq.write %)
           sym #(vector :seon.task/sym (symbol "tq.write" %))
           own #(keyword (str "task-" (handle %)))
           work {:seon.task/plan "Fix `seon.task/create-all!`." :seon.task/proofs '#{tq.write/proof}}
           tasks [(assoc work :seon.task/ns 'tq.write :seon.task/name "a" :seon.task/config {:seon.config.ai/max-tokens 512})
                  {:seon.task/ns 'tq.write :seon.task/name "b" :seon.task/needs #{(sym "a")}}
                  {:seon.task/ns 'tq.write :seon.task/name "c" :seon.task/needs #{(sym "b")}}
                  {:seon.task/ns 'tq.write :seon.task/name "d" :seon.task/needs #{(sym "b")}}
                  {:seon.task/ns 'tq.write :seon.task/name "m" :seon.task/needs #{(sym "c") (sym "d")} :seon.task/target main-branch}]
           answer (task/create-all! {:seon.db/connection connection} tasks)
           database (db/db connection)
           pulled (fn [database task-name] (db/pull database [:seon.task/execution :seon.task/branch :seon.task/target :seon.task/config
                                                              {:seon.task/needs [:seon.task/name]}]
                                                    [:seon.task/id (handle task-name)]))
           names ["a" "b" "c" "d" "m"]]
       (is (= {:seon.task/handles (mapv handle names) :seon.task/changed? true} answer)
           "the answer names every task in write order")
       (is (= 1 (count (db/q '[:find [?tx ...] :in $ [?h ...] :where [?e :seon.task/id ?h ?tx]] database (mapv handle names))))
           "every task is asserted in one transaction")
       (is (= (repeat 5 :held) (mapv (comp :seon.task/execution #(pulled database %)) names)) "every task is written :held")
       (is (= [(own "a") (own "a") (own "c") (own "d") (own "m")] (mapv (comp :seon.task/branch #(pulled database %)) names))
           "a linear run shares its first task's branch; a fan-out and a task with a target each get their own")
       (is (= main-branch (:seon.task/target (pulled database "m"))) "the target is stored")
       (is (= {:seon.config.ai/max-tokens 512} (:seon.task/config (pulled database "a"))) "the config is stored as data")
       (is (= #{"c" "d"} (set (map :seon.task/name (:seon.task/needs (pulled database "m"))))) "needs link the written tasks")
       (let [before (db/commit-id (db/db connection))]
         (is (= {:seon.task/handles (mapv handle names) :seon.task/changed? false}
                (task/create-all! {:seon.db/connection connection} tasks))
             "a repeat write answers the same tasks")
         (is (= before (db/commit-id (db/db connection))) "a repeat write commits nothing"))
       (task/create-all! {:seon.db/connection connection}
                         [(assoc work :seon.task/ns 'tq.write :seon.task/name "e" :seon.task/branch :given)
                          {:seon.task/ns 'tq.write :seon.task/name "f" :seon.task/needs #{(sym "e")}}])
       (is (= [:given :given] (mapv (comp :seon.task/branch #(pulled (db/db connection) %)) ["e" "f"]))
           "a given branch wins, and a linear run continues it")
       (let [before (db/commit-id (db/db connection))
             refusal (task/create-all! {:seon.db/connection connection}
                                       [{:seon.task/ns 'tq.write :seon.task/name "g" :seon.task/needs #{(sym "absent")}}])]
         (is (= :unknown-task (:seon.task/refused refusal)) "a need that resolves to no task is refused")
         (is (= 'tq.write/absent (:seon.error/offending refusal)) "the refusal names the need")
         (is (= before (db/commit-id (db/db connection))) "a refused write commits nothing"))
       (let [refusal (task/create-all! {:seon.db/connection connection}
                                       [{:seon.task/ns 'tq.write :seon.task/name "h" :seon.task/plan "No proofs."}])]
         (is (= :unrunnable (:seon.task/refused refusal)) "a task with no needs is refused when its runnable view is incomplete")
         (is (some? (get-in (:seon.task/explanation refusal) [:seon.task/proofs])) "the explanation names the missing member"))))))

(deftest a-filed-task-becomes-four-linked-tasks-ending-in-a-merge-task
  ;; task-model T1 (`docs/research/agent-platform/2026-10-17/task-model.md#62-the-merge-task-reuses-p4a`)
  (test-support/with-database
   (fn [connection]
     (let [_ (test-support/transacted! connection (test-support/agent-tx (db/db connection) "root"))
           main-branch (get-in (db/db connection) [:config :branch])
           handle (task/task-handle 'tq.filed "bug")
           _ (test-support/transacted! connection
                                       (task/adopt-tasks-tx (db/db connection)
                                                            [{:seon.task/ns 'tq.filed :seon.task/name "bug"
                                                              :seon.task/plan "Fix the bug in `seon.task/create-all!`."
                                                              :seon.task/proofs '#{tq.filed/proof} :seon.task/model :outside}]
                                                            #{handle} {}))
           database (db/db connection)
           filed (task/task-declaration database handle)
           maps (task/from-filed database filed)
           [design _ implement merge-task] maps]
       (is (= 4 (count maps)) "a filed task is a Design, a Prove, an Implement and a merge task")
       (is (= ['tq.filed "bug"] ((juxt :seon.task/ns :seon.task/name) design)) "the first map has the entry's identity")
       (is (= "## Problem\n\nFix the bug in `seon.task/create-all!`." (:seon.task/plan design))
           "a plan with no Problem section is wrapped as one")
       (is (not (contains? (:seon.task/proofs design) 'tq.filed/proof)) "the entry's proofs are not Design's")
       (is (= '#{tq.filed/proof} (:seon.task/proofs implement)) "the entry's proofs are Implement's")
       (is (= #{[:seon.task/sym (task/task-sym 'tq.filed (:seon.task/name implement))] [:seon.task/sym 'tq.filed/bug]}
              (:seon.task/needs merge-task))
           "the merge task needs the implement and design tasks")
       (is (= main-branch (:seon.task/target merge-task)) "the merge task targets main's branch")
       (is (not (contains? merge-task :seon.task/proofs)) "the merge task carries no proofs: its members are derived")
       (is (= (repeat 4 :outside) (mapv :seon.task/model maps)) "the entry's model works every stage")
       (let [{handles :seon.task/handles} (task/create-all! {:seon.db/connection connection :seon.task/basis {handle filed}} maps)
             on-main (db/db connection)
             pulled (mapv #(db/pull on-main [:seon.task/execution :seon.task/branch :seon.task/target :seon.task/plan] [:seon.task/id %])
                          handles)]
         (is (= handle (first handles)) "the design task is the filed task's identity")
         (is (= (repeat 4 :held) (mapv :seon.task/execution pulled)) "all four are written :held on main")
         (is (= (repeat 3 (keyword (str "task-" handle))) (mapv :seon.task/branch (take 3 pulled)))
             "design, prove and implement share one branch")
         (is (= (keyword (str "task-" (last handles))) (:seon.task/branch (last pulled))) "the merge task gets its own branch")
         (is (= main-branch (:seon.task/target (last pulled))) "the merge task's target is stored")
         (is (= (:seon.task/plan design) (:seon.task/plan (first pulled))) "the filed task carries the design plan"))))))

(deftest a-tasks-proof-is-recorded-and-read-under-its-task
  (test-support/with-database
   (fn [connection]
     (let [planned 'tq.chainproof/planned
           plan-b "## Problem\n\nP.\n\n## Approach\n\nA."
           tasks [{:db/id -1 :seon.task/ns 'tq.chain :seon.task/name "a" :seon.task/plan "## Problem\n\nP."
                   :seon.task/proofs #{planned 'tq.chainproof/handoff} :seon.task/next -2}
                  {:db/id -2 :seon.task/ns 'tq.chain :seon.task/name "a-2" :seon.task/proofs #{'tq.chainproof/successor}}
                  {:db/id -3 :seon.task/ns 'tq.chain :seon.task/name "b" :seon.task/plan plan-b :seon.task/proofs #{planned}}]]
       (test-support/task-input connection tasks 'tq.chain/a)
       (test-support/declare-source! connection 'tq.chainproof chain-proofs)
       (task-run! connection #{planned} 'tq.chain/a)
       (task-run! connection #{planned} 'tq.chain/b)
       (is (= [:red :green] [(outcome connection planned 'tq.chain/a) (outcome connection planned 'tq.chain/b)])
           "one template proof holds a result under each task, neither hiding the other")
       (test-support/task-input connection [{:seon.task/ns 'tq.other :seon.task/name "unrelated" :seon.task/plan "U."}]
                                'tq.other/unrelated)
       (is (= [:red :green] [(outcome connection planned 'tq.chain/a) (outcome connection planned 'tq.chain/b)])
           "an unrelated write keeps both results current")
       (is (zero? (:seon.test/executed-count (task-run! connection #{planned} 'tq.chain/b)))
           "an unchanged task's proof is reused, not rerun")
       (let [a-handle (task/task-handle 'tq.chain "a")
             database (db/db connection)
             declared (task/task-declaration database a-handle)]
         (test-support/transacted! connection (task/adopt-tasks-tx database [(assoc declared :seon.task/plan plan-b)]
                                                                   #{a-handle} {a-handle declared})))
       (is (= [nil :green] [(outcome connection planned 'tq.chain/a) (outcome connection planned 'tq.chain/b)])
           "changing a task's plan invalidates its own result only")
       (task-run! connection #{'tq.chainproof/successor 'tq.chainproof/handoff} 'tq.chain/a)
       (is (= :green (outcome connection 'tq.chainproof/handoff 'tq.chain/a))
           "the handoff sees the successor proof's result recorded earlier in the same request")
       (task-run! connection #{'tq.chainproof/unbound} nil)
       (is (= :green (outcome connection 'tq.chainproof/unbound nil))
           "a task-reading test run with no task reads nil, never its tag")))))

(deftest a-branch-call-keeps-its-connection-while-another-call-on-that-branch-releases
  ;; Lane chain-pass (outside-1/outside-2 faults): two per-action calls on one chain branch (db<->flow's pass and
  ;; root's `my.root/merge-requests`) borrowed one connection, so the first call's release closed it under the second
  ;; ("Connection has been released."), and a call in Datahike's opening or releasing window was refused
  ;; ("already has a connection in this process"), failing db<->flow's whole pass
  (test-support/with-database
   (fn [connection]
     (let [store (:seon.store/store (test-support/execution-handle connection))
           branch (keyword (str "per-action-" (random-uuid)))
           first-in (promise) second-in (promise) first-out (promise)]
       (registry/branch! {:seon.store/store store :seon.cluster.registry/from (get-in (db/db connection) [:config :branch])
                          :seon.store/branch branch})
       (try
         (let [outer (future (let [answer (registry/on-branch store branch (fn [_] (deliver first-in true) @second-in :outer))]
                               (deliver first-out true)
                               answer))
               inner (future @first-in
                             (registry/on-branch store branch
                                               (fn [held] (deliver second-in true) @first-out
                                                 (get-in (db/db held) [:config :branch]))))]
           (is (= :outer (deref outer 5000 ::timeout)) "the first call answers")
           (is (= branch (try (deref inner 5000 ::timeout) (catch Exception e (ex-message (ex-cause e)))))
               "the second call still reads its branch after the first released its own"))
         (finally (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})))))))

(deftest the-chains-view-reads-main-and-each-chain-branch
  ;; the chains page: tasks in `:seon.task/next` order with their kind, attempts with main's outcome and the branch's end
  (test-support/with-database
   (fn [connection]
     (let [store (:seon.store/store (test-support/execution-handle connection))
           _ (test-support/transacted! connection (test-support/agent-tx (db/db connection) "root"))
           {branch :seon.chain/branch first-task :seon.chain/first}
           (task/chain! {:seon.db/connection connection :seon.store/store store}
                        [{:seon.task/ns 'tq.view :seon.task/name "design" :seon.task/plan "Design it." :seon.task/proofs '#{tq.view/proof}}
                         {:seon.task/ns 'tq.view :seon.task/step :prove :seon.task/plan "Prove it."}
                         {:seon.task/ns 'tq.view :seon.task/step :implement :seon.task/plan "Implement it."}])
           views #(task/chain-views (db/db connection) {branch (db/branch-database (db/db connection) branch)})
           chain-view #(first (filter (fn [view] (= branch (:seon.chain/branch view))) (views)))]
       (try
         (is (= [["design" :design :running []] ["design-2" :prove :held []] ["design-3" :implement :held []]]
                (mapv (juxt :seon.task/name :seon.chain/kind :seon.task/execution :seon.chain/attempts)
                      (:seon.chain/tasks (chain-view))))
             "the chain's tasks come in next order, each with its kind and execution")
         (is (= :running (:seon.chain/state (chain-view))) "a chain no attempt bailed is running")
         (let [key (registry/on-branch store branch
                                       (fn [on-chain]
                                         (let [tx (task/attempt-tx (db/db on-chain) first-task "root" branch)
                                               key (:seon.task/assignment (first tx))]
                                           (test-support/transacted! on-chain tx)
                                           (test-support/transacted! on-chain [[:db/add [:seon.task/assignment key] :seon.task.attempt/end :capped]
                                                                               [:db/add [:seon.task/assignment key] :seon.task/outcome :closed]
                                                                               [:db/add [:seon.task/assignment key] :seon.task/reason "Out of turns."]])
                                           key)))]
           (is (= [{:seon.task.attempt/number 1 :seon.task.attempt/end :capped :seon.task/outcome :closed
                    :seon.task/reason "Out of turns." :seon.agent/id "root" :seon.task/assignment key :seon.task/branch branch}]
                  (:seon.chain/attempts (first (:seon.chain/tasks (chain-view)))))
               "an attempt joins its end, outcome and reason, names its agent and the branch it works on")
           (is (= :bailed (:seon.chain/state (chain-view))) "a task whose latest attempt root closed bails its chain")
           (test-support/transacted! connection [[:db/retractEntity [:seon.chain/first first-task]]])
           (is (= [:ended 1] ((juxt :seon.chain/state #(count (:seon.chain/attempts (first (:seon.chain/tasks %))))) (chain-view)))
               "a chain whose fact main retracted unfinished stays listed from main's history, ended, with its attempts"))
         (is (= [:unread []] ((juxt :seon.chain/state :seon.chain/tasks)
                              (first (task/chain-views (db/db connection) {}))))
             "a chain whose branch has no database and whose tasks main lacks is unread")
         (finally
           (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})))))))

(deftest a-merged-chains-task-renders-as-done-though-its-execution-stays-running
  ;; TASK-CHAIN §2.5: a finished task stays :running and can never start again; its shown state derives from its attempt.
  (test-support/with-database
   (fn [connection]
     (let [task (fn [outcome] {:seon.task/id "dddddddddddd" :seon.task/ns 'tq.face :seon.task/name "merged"
                               :seon.chain/kind :implement :seon.task/execution :running
                               :seon.chain/attempts [{:seon.task/assignment "eeeeeeeeeeee" :seon.task.attempt/number 1
                                                      :seon.task.attempt/end :done :seon.task/outcome outcome :seon.agent/id "attempt/two"}]})
           shown (fn [outcome]
                   (let [view {:seon.chain/branch :chain-y :seon.chain/first "dddddddddddd" :seon.chain/state :done
                               :seon.chain/tasks [(task outcome)]}
                         html (task/chain-html {:seon.render/value view :seon.db/db (db/db connection)})]
                     (->> (tree-seq coll? seq html)
                          (filter #(and (vector? %) (map? (second %)) (= "seon-chain-task-execution" (:class (second %)))))
                          (mapv #(nth % 2)))))]
       (is (= ["done"] (shown :merged)) "a merged task shows done")
       (is (= ["done"] (shown :done)) "a done task shows done")))))

(deftest the-chain-face-composes-its-tasks-and-attempts-with-links-to-their-agents
  (test-support/with-database
   (fn [connection]
  (let [attempt {:seon.task/assignment "aaaaaaaaaaaa" :seon.task.attempt/number 1 :seon.task.attempt/end :capped
                 :seon.task/outcome :closed :seon.task/reason "Out of turns." :seon.agent/id "attempt/one"}
        view {:seon.chain/branch :chain-x :seon.chain/first "bbbbbbbbbbbb" :seon.chain/state :bailed
              :seon.chain/tasks [{:seon.task/id "bbbbbbbbbbbb" :seon.task/ns 'tq.face :seon.task/name "first"
                                  :seon.chain/kind :design :seon.task/execution :held :seon.chain/attempts [attempt]}
                                 {:seon.task/id "cccccccccccc" :seon.task/ns 'tq.face :seon.task/name "second"
                                  :seon.chain/kind :implement :seon.task/execution :held :seon.chain/attempts []}]}
        html (task/chain-html {:seon.render/value view :seon.db/db (db/db connection)})
        nodes (tree-seq coll? seq html)
        elements (filter #(and (vector? %) (keyword? (first %))) nodes)
        attributes (fn [tag] (keep #(when (and (= tag (first %)) (map? (second %))) (second %)) elements))]
    (is (= ["bbbbbbbbbbbb" "cccccccccccc"] (mapv :data-task (filter :data-task (attributes :li))))
        "the chain's tasks render in order, one item each")
    (is (= ["/agent/attempt%2Fone" "/agent/attempt%2Fone/debug"] (mapv :href (attributes :a)))
        "an attempt links to its agent's page and to its debug page, which shows what each model call was sent")
    (is (= ["bailed"] (mapv :data-state (filter :data-state (attributes :article)))) "the chain states its state")
    (is (some #(re-find #"seon-chain-loud" (:class %)) (filter :data-task (attributes :li)))
        "a bailed task is marked loud")
    (is (some #{"Design" "Implement"} nodes) "each task states its kind")))))

(deftest a-one-task-chain-costs-what-it-writes
  ;; Live run 10 (docs/research/agent-platform/2026-10-18/live-run-10.md): root's one request starting four
  ;; single-task chains allocated 574.9 MB in 1,048 ms on default ab89888ef, about 144 MB per chain. A chain of one
  ;; writes one task (about twenty datoms) through the task writer on a new branch (a `datahike.api/branch!` pointer)
  ;; and one chain fact on main, and validates the first task's runnable view: work proportional to the task.
  (test-support/with-database
   (fn [connection]
     (let [store (:seon.store/store (test-support/execution-handle connection))
           context {:seon.db/connection connection :seon.store/store store}
           _ (test-support/transacted! connection (test-support/agent-tx (db/db connection) "root"))
           handle-of #(task/task-handle 'tq.cost %)
           _ (test-support/transacted! connection
                                       (task/adopt-tasks-tx (db/db connection)
                                                            [{:seon.task/ns 'tq.cost :seon.task/name "template"
                                                              :seon.task/instructions "Check with `seon.task/chain!`."
                                                              :seon.task/proofs '#{tq.cost/proof}}]
                                                            #{(handle-of "template")} {}))
           owned (task/template (db/db connection) 'tq.cost/template)
           one (fn [task-name] [(merge owned {:seon.task/ns 'tq.cost :seon.task/name task-name
                                              :seon.task/plan "Cost one chain of `seon.task/chain!`."})])
           ;; the first chain pays this fixture's first derivations; the second is the one measured
           _ (task/chain! context (one "warm"))
           measured (profile/measure #(task/chain! context (one "measured")))]
       (is (= (keyword (str "chain-" (handle-of "measured"))) (:seon.chain/branch (:seon.profile/value measured))))
       (is (< (:seon.profile/alloc-bytes measured) (* 24 1024 1024))
           "a chain of one allocates in proportion to the task it writes")))))
