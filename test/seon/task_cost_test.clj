(ns seon.task-cost-test
  "Cost proofs of the task reader's hot lookups."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.task :as task] [seon.cost :as cost]
            [seon.render.ui :as ui]))

(defn tasks-db
  "The input of size n: a main db holding n tasks, and the id of one of them."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :keyword :string]]}
  [n]
  (let [db (ts/test-db)
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
     :sizes [100 1000 10000]
     ;; measured 2026-10-20 (was a Datalog query: 99 us, 108 KB): the seek itself allocates
     ;; 23-32 KB in our Datahike / persistent-sorted-set on Jolt, on the whole process's count.
     ;; Re-measured 2026-10-23 on the proof's own thread (18b0774; Chez 80e4b9f, load 17): 17 ->
     ;; 21 KB and 18 -> 21 us. The record a ratchet compares with is this measure's
     :overrun {:task "seon.db/avet-seek-allocates" :measured {:ns 21000.0 :bytes 21400.0}}}))

(def ^:private explicit-attempt-pull
  "The attempt pull before the wildcard (2026-10-22, research row 20): what the reader read."
  [:seon.attempt/id {:seon.attempt/task [:seon.task/id]} :seon.attempt/status :seon.attempt/boot :seon.attempt/agent
   :seon.attempt/pid :seon.attempt/start-ms :seon.attempt/end-ms :seon.attempt/branch :seon.attempt/model
   :seon.attempt/max-turns :seon.attempt/outcome :seon.attempt/reason :seon.attempt/turns :seon.attempt/model-calls
   :seon.attempt/tokens :seon.attempt/merged-t :seon.attempt/basis-t :seon.attempt/plan :seon.attempt/uncontracted
   :seon.attempt/unmerged :seon.attempt/changed
   {:seon.attempt/results [:seon.proof/test :seon.proof/status]}
   {:seon.attempt/conflicts [:seon.merge/key :seon.merge/kind :seon.merge/base :seon.merge/ours :seon.merge/theirs]}])

(defn attempts-db
  "The input of size n: a main db holding n ended attempts of one task, each with what an
  ended attempt carries on main: its end members (proof results, a commit) and the problem
  members the judge writes (the error that ended it, a red test's assertions, a cost report,
  the turns' waste)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db]]}
  [n]
  (let [db (ts/test-db)
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "t" :seon.task/plan "p"}]))
        report {:rows [{:n 100 :measured {:ns 1200.0 :bytes 900.0} :expected {:ns 1000.0 :bytes 800.0}}] :verdict {:ok? false :over [{:dim :ns :n 100 :ratio 1.2}] :steeper [] :under [] :unmerged []}}]
    [(d/db-with db (vec (for [i (range n) :let [aid (str "demo/t#" i)]
                              tx (concat (task/attempt-start-tx aid "demo/t" "boot-1" (keyword (str "a" i)) {:branch :b :model "m" :max-turns 4} 1000 "p")
                                         [(assoc (task/end-members {:outcome :error :turns 3 :model-calls 3 :tokens 900 :results {'demo-test/t :fail}
                                                                    :uncontracted #{} :reason "the model failed"} 2000 7)
                                                 :seon.attempt/id aid :seon.attempt/commit "c0ffee"
                                                 :seon.attempt/error {:seon.error/type :seon.model/no-provider :seon.error/operation 'seon.model/complete :seon.error/message "no model provider is configured"}
                                                 :seon.attempt/failures {'demo-test/t [{:type :fail :message "sums" :expected "(= 3 (f 1 2))" :actual "(= 3 4)"}]}
                                                 :seon.attempt/costs {'demo-test/c report}
                                                 :seon.attempt/waste {'seon.context/resolve-ref {:forms 2 :turns 1}})])]
                          tx)))]))

(deftest an-attempt-row-is-what-it-was-under-the-wildcard-pull
  ;; land6 review: the wildcard pull (research row 20) brings every stored attempt member
  ;; (its error, failures, cost reports, waste, commit) into the value; the dashboard's row
  ;; reads its nine members, so it renders exactly what the explicit pull's value rendered
  (let [[db] (attempts-db 3)
        old (#'task/attempt-value (d/pull db explicit-attempt-pull [:seon.attempt/id "demo/t#1"]))
        new (task/attempt db "demo/t#1")]
    (is (contains? new :seon.attempt/error) "the wildcard brings the problem members")
    (is (= (ui/attempt-row old {}) (ui/attempt-row new {})))))

(deftest reading-every-attempt-costs-what-its-datoms-cost
  (cost/proof
    {:of 'seon.task/attempts :input 'seon.task-cost-test/attempts-db
     :n "ended attempts on main, each with its end and problem members"
     :work (str "one query for the attempt entities, then one wildcard pull each: every datom of the attempt read "
                "once, its EDN members (error, failures, cost report, waste) decoded, its component results "
                "pulled, then attempt-value (one contracted call). Measured 2026-10-22 at 100 attempts: 14.9 ms "
                "and 15.8 MB (149 us, 158 KB each); the explicit 23-attribute pull it replaced read the same "
                "input in 32.6 ms and 30.1 MB (one lookup per named attribute, absent ones included)")
     :growth :linear
     :per-n {:ns 200000 :bytes 200000 :calls 1}
     :fixed {:ns 100000 :bytes 100000 :calls 1}
     :sizes [10 100 300]}))
