(ns seon.task-test
  "The task tree (runtime-follows-db §2): child tasks and needs as facts, the writer's
  needs-cycle refusal, the attempt and firing facts adopted on a branch of the live store."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is are testing]]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.id :as id]
            [seon.cluster.registry :as registry]
            [seon.cluster.store]
            [seon.profile :as profile]
            [seon.schema :as schema]
            [seon.task :as task]
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
          :seon.task/branch :tq-tree :seon.task.attempt/end :error :seon.task.attempt/relaunch "The execution is repaired."
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
         (is (= {:seon.task.attempt/end :error :seon.task.attempt/relaunch "The execution is repaired."
                 :seon.task.attempt/reason "The proof asserts the old shape." :seon.task.attempt/commit commit
                 :seon.task/attempt-of 'tq.tree/a :seon.task.attempt/target 'tq.tree/a}
                (-> (db/pull database [:seon.task.attempt/end :seon.task.attempt/relaunch
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
    (let [allowed '#{seon.task/obligations seon.fn/task-links seon.task/candidate-dangling seon.task/open-tasks
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
       (is (= #{:seon.task/instructions :seon.task/proofs} owned) "the schema property marks exactly instructions and proofs")
       (is (contains? task :seon.task/next) "a task may link its next")
       (is (not-any? (members-of :seon.task/declaration) [:seon.task/next :seon.task/execution])
           "next and execution are never declared")))))

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
                 :seon.task/handles (mapv handle names)}
                answer)
             "the answer names the branch, the first task and every task in chain order")
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
         (let [from (chain! context [(assoc owned :seon.task/ns 'tq.chain :seon.task/name "again")] {:seon.chain/from branch})
               _ (is (= from-branch (:seon.chain/branch from)) "a chain's branch is named by its first task")
               database (db/db connection)]
           (is (= main-branch (:seon.branch/target (registry/fork-of (db/branch-database database from-branch)
                                                                      from-branch)))
               "a chain started from another branch merges toward main")
           (is (= (registry/base-commit-id database branch) (registry/base-commit-id database from-branch))
               "its base is the main commit the first chain forked at")
           (is (= :running (:seon.task/execution (pulled (db/branch-database database from-branch) "fix")))
               "it starts from that branch's head, holding its tasks"))
         (is (< (:seon.profile/ms measured) 1000) "the durable create is measured, expected about 100 ms")
         (finally
           (doseq [b [branch from-branch] :when (contains? (registry/branches store) b)]
             (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))))))))
