(ns seon.task-cost-test
  "Cost proofs of the task reader's hot lookups."
  (:refer-clojure :exclude [defn defn-])
  (:require [my.test :as mt] [clojure.test :refer [deftest is]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.task :as task] [seon.cost :as cost]
            [seon.render.ui :as ui]))

(defn tasks-db
  "The input of size n: a main db holding n tasks, and the id of one of them."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :keyword :string]]}
  [n]
  (let [db (mt/world)
        decls (vec (for [i (range n)] {:seon.task/ns 'demo :seon.task/name (str "t" i) :seon.task/plan "p"}))]
    [(d/db-with db (task/create-tx db decls)) :seon.task/id (str "demo/t" (quot n 2))]))

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

(def ^:private explicit-run-pull
  "The run pull before the wildcard (2026-10-22, research row 20): what the reader read."
  [:seon.run/id {:seon.run/task [:seon.task/id]} :seon.run/status :seon.run/boot {:seon.run/agent [:seon.agent/id]}
   :seon.run/pid :seon.run/start-ms :seon.run/end-ms :seon.run/branch :seon.run/model
   :seon.run/max-turns :seon.run/outcome :seon.run/reason :seon.run/turns :seon.run/model-calls
   :seon.run/tokens :seon.run/merged-t :seon.run/basis-t :seon.run/plan :seon.run/uncontracted
   :seon.run/unmerged :seon.run/changed
   {:seon.run/results [:seon.proof/test :seon.proof/status]}
   {:seon.run/conflicts [:seon.merge/key :seon.merge/kind :seon.merge/base :seon.merge/ours :seon.merge/theirs]}])

(defn runs-db
  "The input of size n: a main db holding n ended runs of one task, each with what an
  ended run carries on main: its end members (proof results, a commit) and the problem
  members the judge writes (the error that ended it, a red test's assertions, a cost report,
  the turns' waste)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db]]}
  [n]
  (let [db (mt/world)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "t" :seon.task/plan "p"}]))
        report {:rows [{:n 100 :measured {:ns 1200.0 :bytes 900.0} :expected {:ns 1000.0 :bytes 800.0}}] :verdict {:ok? false :over [{:dim :ns :n 100 :ratio 1.2}] :steeper [] :under [] :unmerged []}}]
    [(d/db-with db (vec (for [i (range n) :let [aid (str "demo/t#" i)]
                              tx (concat (task/run-start-tx aid "demo/t" "boot-1" (keyword (str "a" i)) {:branch :b :model "m" :max-turns 4} 1000 "p")
                                         [(assoc (task/end-members {:outcome :error :turns 3 :model-calls 3 :tokens 900 :results {'demo-test/t :fail}
                                                                    :uncontracted #{} :reason "the model failed"} 2000 7)
                                                 :seon.run/id aid :seon.run/commit "c0ffee"
                                                 :seon.run/error {:seon.error/type :seon.model/no-provider :seon.error/operation 'seon.model/complete :seon.error/message "no model provider is configured"}
                                                 :seon.run/failures {'demo-test/t [{:type :fail :message "sums" :expected "(= 3 (f 1 2))" :actual "(= 3 4)"}]}
                                                 :seon.run/costs {'demo-test/c report}
                                                 :seon.run/waste {'seon.docs/resolve-ref {:forms 2 :turns 1}})])]
                          tx)))]))

(deftest a-run-row-is-what-it-was-under-the-wildcard-pull
  ;; land6 review: the wildcard pull (research row 20) brings every stored run member
  ;; (its error, failures, cost reports, waste, commit) into the value; the dashboard's row
  ;; reads its nine members, so it renders exactly what the explicit pull's value rendered
  (let [[db] (runs-db 3)
        old (#'task/run-value (d/pull db explicit-run-pull [:seon.run/id "demo/t#1"]))
        new (task/run db "demo/t#1")]
    (is (contains? new :seon.run/error) "the wildcard brings the problem members")
    (is (= (ui/run-row {} old) (ui/run-row {} new)))))

(deftest reading-every-run-costs-what-its-datoms-cost
  (cost/proof
    {:of 'seon.task/runs :input 'seon.task-cost-test/runs-db
     :n "ended runs on main, each with its end and problem members"
     :work (str "one query for the run entities, then one wildcard pull each: every datom of the run read "
                "once, its EDN members (error, failures, cost report, waste) decoded, its component results "
                "pulled, then run-value (one contracted call). Measured 2026-10-22 at 100 runs: 14.9 ms "
                "and 15.8 MB (149 us, 158 KB each); the explicit 23-attribute pull it replaced read the same "
                "input in 32.6 ms and 30.1 MB (one lookup per named attribute, absent ones included)")
     :growth :linear
     ;; 2026-10-31 (shrink-tests-4): on the world, sizes [10 30 100]: 1.40M ns / 1.45 MB at 10, 3.87M / 4.18 MB at 30,
     ;; 12.4M / 13.6 MB at 100 runs: ~124 us and 135 KB a run (the larger sizes read the same per run)
     :per-n {:ns 130000 :bytes 140000 :calls 1}
     :fixed {:ns 100000 :bytes 100000 :calls 1}
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
