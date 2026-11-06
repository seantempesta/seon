(ns seon.task-cost-test
  "Cost proofs of the task reader's hot lookups."
  (:refer-clojure :exclude [defn defn-])
  (:require [my.test :as mt] [clojure.test :refer [deftest is]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.task :as task] [seon.cost :as cost]))

(defn tasks-db
  "The input of size n: a main db holding n tasks, and the id of one of them."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :keyword :string]]}
  [n]
  (let [db (mt/world)
        decls (vec (for [i (range n)] {:seon.task/ns 'demo :seon.task/name (str "t" i) :seon.task/plan "p"}))]
    [(d/db-with db (task/create-tx db decls)) :seon.task/id (str "demo/t" (quot n 2))]))

(defn replaced-db
  "The input of size n: a db keeping history where one note's text was replaced n times, and the note."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :int]]}
  [n]
  (let [db0 (d/db-with (mt/world {:history? true}) [{:db/id "n" :note/text "t0" :note/agent :x}])
        e (:e (first (d/datoms db0 :avet :note/agent :x)))]
    [(reduce (fn [db i] (d/db-with db [[:db/add e :note/text (str "t" i)]])) db0 (range 1 (inc n))) e]))

(deftest an-entitys-history-costs-its-rows
  ;; docs/storage.md#history
  (cost/proof
    {:of 'seon.db/history :input 'seon.task-cost-test/replaced-db
     :n "times the entity's attribute was replaced"
     :work "one seek of the entity in the history index, then a row per datom, each with one seek of its transaction's time"
     :growth :linear
     :per-n {:calls 1 :bytes 1}
     :fixed {:calls 1 :bytes 1}
     :sizes [10 40 160]}))

(deftest finding-a-task-by-its-id-is-one-index-seek
  (cost/proof
    {:of 'seon.db/eid :input 'seon.task-cost-test/tasks-db
     :n "tasks on main"
     :work (str "one seek in the unique attribute's AVET index: 2-3 B-tree nodes, a binary search of ~6 datom "
                "comparisons (~100 ns each on Jolt) per node, then one datom answered; no query planning, nothing "
                "per task. About 3 us and 1.3 KB at 10k tasks")
     :growth :log
     :per-n {:ns 150 :bytes 50}
     :fixed {:ns 1000 :bytes 600 :calls 1}
     :sizes [100 300 1000 10000]
     ;; measured 2026-10-20 (was a Datalog query: 99 us, 108 KB): the seek itself allocates
     ;; 23-32 KB in our Datahike / persistent-sorted-set on Jolt, on the whole process's count.
     ;; Re-measured 2026-10-23 on the proof's own thread (18b0774; Chez 80e4b9f, load 17): 17 ->
     ;; 21 KB and 18 -> 21 us. The record a ratchet compares with is this measure's
     :overrun {:task "seon.db/avet-seek-allocates" :measured {:ns 21000.0 :bytes 21400.0}}}))

(defn ended-db
  "The input of size n: a main db holding n tasks whose agents ended, each with what a judging carries on main: its
  reason, error, a commit, a red test's assertions, a cost report and the turns' waste (outcome-tx's members), and its agent."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db]]}
  [n]
  (let [db (mt/world)
        ids (mapv #(str "demo/t" %) (range n))
        db (d/db-with db (task/create-tx db (mapv (fn [i] {:seon.task/ns 'demo :seon.task/name (str "t" i) :seon.task/plan "p"}) (range n))))
        report {:rows [{:n 100 :measured {:ns 1200.0 :bytes 900.0} :expected {:ns 1000.0 :bytes 800.0}}] :verdict {:ok? false :over [{:dim :ns :n 100 :ratio 1.2}] :steeper [] :under [] :unmerged []}}
        started (d/db-with db (vec (mapcat (fn [i id] (task/start-tx db id "boot-1" (keyword (str "a" i)) {:branch :b :model "m" :max-turns 4} 1000)) (range) ids)))]
    [(d/db-with started
                (vec (mapcat (fn [id]
                               (task/outcome-tx started id :running :dead
                                                {:seon.task/reason "the model failed" :seon.task/commit "c0ffee"
                                                 :seon.task/error {:seon.error/type :seon.model/no-provider :seon.error/operation 'seon.model/complete :seon.error/message "no model provider is configured"}
                                                 :seon.task/failures {'demo-test/t [{:type :fail :message "sums" :expected "(= 3 (f 1 2))" :actual "(= 3 4)"}]}
                                                 :seon.task/costs {'demo-test/c report}
                                                 :seon.task/waste {'seon.doc/resolve-ref {:forms 2 :turns 1}}}))
                             ids)))]))

(deftest a-task-reads-with-every-member-its-judging-wrote
  ;; land6 review: the wildcard pull (research row 20) brings every stored member (its error, failures, cost reports,
  ;; waste, commit) into the value, so a row of a page draws from it what the judging recorded
  (let [[db] (ended-db 3)
        tk (task/task db "demo/t1")]
    (is (= [:dead "c0ffee" "the model failed"] ((juxt :seon.task/outcome :seon.task/commit :seon.task/reason) tk)))
    (is (every? #(contains? tk %) [:seon.task/error :seon.task/failures :seon.task/costs :seon.task/waste]) "the wildcard brings the problem members")
    (is (= "boot-1/a1" (:seon.task/agent tk)) "and names its agent by key")))

(deftest reading-every-task-costs-what-its-datoms-cost
  (cost/proof
    {:of 'seon.task/tasks :input 'seon.task-cost-test/ended-db
     :n "tasks whose agents ended on main, each with its judging's and problem members"
     :work (str "one query for the task entities, then one wildcard pull each: every datom of the task read "
                "once, its EDN members (error, failures, cost report, waste) decoded, then task-value (one contracted "
                "call) and its accumulated plan and proofs. Measured 2026-10-22 at 100 runs (the entity this replaced): "
                "14.9 ms and 15.8 MB (149 us, 158 KB each); the explicit 23-attribute pull it replaced read the same "
                "input in 32.6 ms and 30.1 MB (one lookup per named attribute, absent ones included)")
     :growth :linear
     ;; 2026-10-31 (shrink-tests-4), as runs on the world, sizes [10 30 100]: 1.40M ns / 1.45 MB at 10, 3.87M / 4.18 MB at 30,
     ;; 12.4M / 13.6 MB at 100: ~124 us and 135 KB each (the larger sizes read the same per entity)
     ;; 2026-11-04, as tasks: 0.60M ns / 0.94 MB and 23 contracted calls at 10 (60 us, 94 KB, 2 calls each: task-value and
     ;; accumulate; the runs it replaced made one, run-value). The ns and bytes bounds stay the runs' own
     :per-n {:ns 130000 :bytes 140000 :calls 2}
     :fixed {:ns 100000 :bytes 100000 :calls 3}
     :sizes [10 30 100]}))

(defn carried-of
  "The input of size n: carry-tx's three dbs (base, branch, main) over n tasks, each with a plan and
  two proofs, the branch having improved one task's plan."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :seon.host/db]]}
  [n]
  (let [db (mt/world)
        decls (vec (for [i (range n)] {:seon.task/ns 'demo :seon.task/name (str "t" i) :seon.task/plan (str "Make t" i " true.")
                                       :seon.task/proofs #{(symbol "demo-test" (str "t" i "-test")) 'demo-test/other}}))
        base (d/db-with db (task/create-tx db decls))]
    [base (d/db-with base [[:db/add [:seon.task/id "demo/t0"] :seon.task/plan "Better."]]) base]))

(deftest carrying-a-branch-reads-each-carried-datom-once
  (cost/proof
    {:of 'seon.task/carry-tx :input 'seon.task-cost-test/carried-of
     :n "tasks on main"
     :work (str "the carried attributes' datoms (a plan and two proofs a task) read from :aevt on each of the three "
                "dbs, one map entry each, a set member's cardinality read from the db's attribute index "
                "(datahike.db.utils/multival?), and the compare per member; nothing is written for an unchanged task")
     :growth :linear
     ;; measured: 0.46 / 3.09 / 28.8 MB at 10 / 100 / 1,000 tasks, 2 calls; it was 774 MB at 100 when each datom's
     ;; cardinality read (d/schema main), which rebuilds the whole schema map
     ;; 2026-10-31 (shrink-tests-4): on the world, sizes [10 30 100]: 323 KB at 10, 809 KB at 30, 2.57 MB at 100: ~25 KB a task
     :per-n {:bytes 26000}
     :fixed {:calls 2 :bytes 100000}
     :sizes [10 30 100]}))

(defn loaded-of
  "The input of size n: a main db holding n tasks loaded from their file (each with its declaration stored), and the
  same file read again."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db [:map-of :symbol :any]]]}
  [n]
  (let [db (mt/world)
        files {'demo (vec (for [i (range n)] {:seon.task/name (str "t" i) :seon.task/ns 'demo :seon.task/plan "p"
                                              :seon.task/needs (if (zero? (mod i 5)) #{} #{(str "demo/t" (dec i))})}))}]
    [(d/db-with db (task/load-tx db files)) files]))

(deftest an-unchanged-load-reads-four-members-of-each-task
  (cost/proof
    {:of 'seon.task/load-tx :input 'seon.task-cost-test/loaded-of
     :n "tasks on main, all unchanged"
     :work (str "four :aevt scans (id, ns, execution, needs: ~4 datoms a task, one map entry each) in place of a "
                "pull of every member of every task (`seon.task/tasks`: ~93 KB a task on main, 46 MB for 452), and "
                "the stored declaration of each task decoded to compare it; nothing is written")
     :growth :linear
     :per-n {:ticks 20000.0 :bytes 30000.0 :calls 6.0}
     :fixed {:ticks 100000.0 :bytes 200000.0 :calls 20.0}
     :sizes [10 100 300]}))
